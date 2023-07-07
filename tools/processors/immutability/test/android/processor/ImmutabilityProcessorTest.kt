/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.processor.immutability.IMMUTABLE_ANNOTATION_NAME
import android.processor.immutability.ImmutabilityProcessor
import android.processor.immutability.MessageUtils
import com.google.common.truth.Expect
import com.google.testing.compile.CompilationSubject.assertThat
import com.google.testing.compile.Compiler.javac
import com.google.testing.compile.JavaFileObjects
import org.junit.Rule
import org.junit.Test
import java.util.*
import javax.tools.JavaFileObject

class ImmutabilityProcessorTest {

    companion object {
        private const val PACKAGE_PREFIX = "android.processor.immutability"
        private const val DATA_CLASS_NAME = "DataClass"
        private val ANNOTATION = JavaFileObjects.forResource("Immutable.java")

        private val FINAL_CLASSES = listOf(
            JavaFileObjects.forSourceString(
                "$PACKAGE_PREFIX.NonFinalClassFinalFields",
                /* language=JAVA */ """
                    package $PACKAGE_PREFIX;

                    public class NonFinalClassFinalFields {
                        private final String finalField;
                        public NonFinalClassFinalFields(String value) {
                            this.finalField = value;
                        }
                    }
                """.trimIndent()
            ),
            JavaFileObjects.forSourceString(
                "$PACKAGE_PREFIX.NonFinalClassNonFinalFields",
                /* language=JAVA */ """
                    package $PACKAGE_PREFIX;

                    public class NonFinalClassNonFinalFields {
                        private String nonFinalField;
                    }
                """.trimIndent()
            ),
            JavaFileObjects.forSourceString(
                "$PACKAGE_PREFIX.FinalClassFinalFields",
                /* language=JAVA */ """
                    package $PACKAGE_PREFIX;

                    public final class FinalClassFinalFields {
                        private final String finalField;
                        public FinalClassFinalFields(String value) {
                            this.finalField = value;
                        }
                    }
                """.trimIndent()
            ),
            JavaFileObjects.forSourceString(
                "$PACKAGE_PREFIX.FinalClassNonFinalFields",
                /* language=JAVA */ """
                    package $PACKAGE_PREFIX;

                    public final class FinalClassNonFinalFields {
                        private String nonFinalField;
                    }
                """.trimIndent()
            )
        )
    }

    @get:Rule
    val expect = Expect.create()

    @Test
    fun validInterface() = test(
        source = JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.$DATA_CLASS_NAME",
            /* language=JAVA */ """
                package $PACKAGE_PREFIX;

                import $IMMUTABLE_ANNOTATION_NAME;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.List;

                @Immutable
                public interface $DATA_CLASS_NAME {
                    InnerInterface DEFAULT = new InnerInterface() {
                        @Override
                        public String getValue() {
                            return "";
                        }
                        @Override
                        public List<String> getArray() {
                            return Collections.emptyList();
                        }
                    };

                    String getValue();
                    ArrayList<String> getArray();
                    InnerInterface getInnerInterface();

                    @Immutable
                    interface InnerInterface {
                        String getValue();
                        List<String> getArray();
                    }
                }
                """.trimIndent()
        ), errors = emptyList()
    )

    @Test
    fun abstractClass() = test(
        JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.$DATA_CLASS_NAME",
            /* language=JAVA */ """
                package $PACKAGE_PREFIX;

                import $IMMUTABLE_ANNOTATION_NAME;
                import java.util.Map;

                @Immutable
                public abstract class $DATA_CLASS_NAME {
                    public static final String IMMUTABLE = "";
                    public static final InnerClass NOT_IMMUTABLE = null;
                    public static InnerClass NOT_FINAL = null;

                    // Field finality doesn't matter, methods are always enforced so that future
                    // field compaction or deprecation is possible
                    private final String fieldFinal = "";
                    private String fieldNonFinal;
                    public abstract void sideEffect();
                    public abstract String[] getArray();
                    public abstract InnerClass getInnerClassOne();
                    public abstract InnerClass getInnerClassTwo();
                    @Immutable.Ignore
                    public abstract InnerClass getIgnored();
                    public abstract InnerInterface getInnerInterface();

                    public abstract Map<String, String> getValidMap();
                    public abstract Map<InnerClass, InnerClass> getInvalidMap();

                    public static final class InnerClass {
                        public String innerField;
                        public String[] getArray() { return null; }
                    }

                    public interface InnerInterface {
                        String[] getArray();
                        InnerClass getInnerClass();
                    }
                }
                """.trimIndent()
        ), errors = listOf(
            nonInterfaceClassFailure(line = 7),
            nonInterfaceReturnFailure(line = 9),
            staticNonFinalFailure(line = 10),
            nonInterfaceReturnFailure(line = 10),
            memberNotMethodFailure(line = 14),
            memberNotMethodFailure(line = 15),
            voidReturnFailure(line = 16),
            arrayFailure(line = 17),
            nonInterfaceReturnFailure(line = 18),
            nonInterfaceReturnFailure(line = 19),
            classNotImmutableFailure(line = 22, className = "InnerInterface"),
            nonInterfaceReturnFailure(line = 25, prefix = "Key InnerClass"),
            nonInterfaceReturnFailure(line = 25, prefix = "Value InnerClass"),
            classNotImmutableFailure(line = 27, className = "InnerClass"),
            nonInterfaceClassFailure(line = 27),
            memberNotMethodFailure(line = 28),
            arrayFailure(line = 29),
            arrayFailure(line = 33),
            nonInterfaceReturnFailure(line = 34),
        )
    )

    @Test
    fun finalClasses() = test(
        JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.$DATA_CLASS_NAME",
            /* language=JAVA */ """
            package $PACKAGE_PREFIX;

            import java.util.List;

            @Immutable
            public interface $DATA_CLASS_NAME {
                NonFinalClassFinalFields getNonFinalFinal();
                List<NonFinalClassNonFinalFields> getNonFinalNonFinal();
                FinalClassFinalFields getFinalFinal();
                List<FinalClassNonFinalFields> getFinalNonFinal();

                @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
                NonFinalClassFinalFields getPolicyNonFinalFinal();

                @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
                List<NonFinalClassNonFinalFields> getPolicyNonFinalNonFinal();

                @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
                FinalClassFinalFields getPolicyFinalFinal();

                @Immutable.Policy(exceptions = {Immutable.Policy.Exception.FINAL_CLASSES_WITH_FINAL_FIELDS})
                List<FinalClassNonFinalFields> getPolicyFinalNonFinal();
            }
            """.trimIndent()
        ), errors = listOf(
            nonInterfaceReturnFailure(line = 7),
            nonInterfaceReturnFailure(line = 8, index = 0),
            nonInterfaceReturnFailure(line = 9),
            nonInterfaceReturnFailure(line = 10, index = 0),
            classNotFinalFailure(line = 13, "NonFinalClassFinalFields"),
        ), otherErrors = mapOf(
            FINAL_CLASSES[1] to listOf(
                memberNotMethodFailure(line = 4),
            ),
            FINAL_CLASSES[3] to listOf(
                memberNotMethodFailure(line = 4),
            ),
        )
    )

    @Test
    fun superClass() {
        val superClass = JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.SuperClass",
            /* language=JAVA */ """
            package $PACKAGE_PREFIX;

            import java.util.List;

            public interface SuperClass {
                InnerClass getInnerClassOne();

                final class InnerClass {
                    public String innerField;
                }
            }
            """.trimIndent()
        )

        val dataClass = JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.$DATA_CLASS_NAME",
            /* language=JAVA */ """
            package $PACKAGE_PREFIX;

            import java.util.List;

            @Immutable
            public interface $DATA_CLASS_NAME extends SuperClass {
                String[] getArray();
            }
            """.trimIndent()
        )

        test(
            sources = arrayOf(superClass, dataClass),
            fileToErrors = mapOf(
                superClass to listOf(
                    classNotImmutableFailure(line = 5, className = "SuperClass"),
                    nonInterfaceReturnFailure(line = 6),
                    nonInterfaceClassFailure(8),
                    classNotImmutableFailure(line = 8, className = "InnerClass"),
                    memberNotMethodFailure(line = 9),
                ),
                dataClass to listOf(
                    arrayFailure(line = 7),
                )
            )
        )
    }

    @Test
    fun ignoredClass() = test(
        JavaFileObjects.forSourceString(
            "$PACKAGE_PREFIX.$DATA_CLASS_NAME",
            /* language=JAVA */ """
            package $PACKAGE_PREFIX;

            import java.util.List;
            import java.util.Map;

            @Immutable
            public interface $DATA_CLASS_NAME {
                IgnoredClass getInnerClassOne();
                NotIgnoredClass getInnerClassTwo();
                Map<String, IgnoredClass> getInnerClassThree();
                Map<String, NotIgnoredClass> getInnerClassFour();

                @Immutable.Ignore
                final class IgnoredClass {
                    public String innerField;
                }

                final class NotIgnoredClass {
                    public String innerField;
                }
            }
            """.trimIndent()
        ), errors = listOf(
            nonInterfaceReturnFailure(line = 9),
            nonInterfaceReturnFailure(line = 11, prefix = "Value NotIgnoredClass"),
            classNotImmutableFailure(line = 18, className = "NotIgnoredClass"),
            nonInterfaceClassFailure(line = 18),
            memberNotMethodFailure(line = 19),
        )
    )

    private fun test(
        source: JavaFileObject,
        errors: List<CompilationError>,
        otherErrors: Map<JavaFileObject, List<CompilationError>> = emptyMap(),
    ) = test(
        sources = arrayOf(source),
        fileToErrors = otherErrors + (source to errors),
    )

    private fun test(
        vararg sources: JavaFileObject,
        fileToErrors: Map<JavaFileObject, List<CompilationError>> = emptyMap(),
    ) {
        val compilation = javac()
            .withProcessors(ImmutabilityProcessor())
            .compile(FINAL_CLASSES + ANNOTATION + sources)

        fileToErrors.forEach { (file, errors) ->
            errors.forEach { error ->
                try {
                    assertThat(compilation)
                        .hadErrorContaining(error.message)
                        .inFile(file)
                        .onLine(error.line)
                } catch (e: AssertionError) {
                    // Wrap the exception so that the line number is logged
                    val wrapped = AssertionError("Expected $error, ${e.message}").apply {
                        stackTrace = e.stackTrace
                    }

                    // Wrap again with Expect so that all errors are reported. This is very bad code
                    // but can only be fixed by updating compile-testing with a better Truth Subject
                    // implementation.
                    expect.that(wrapped).isNull()
                }
            }
        }

        expect.that(compilation.errors().size).isEqualTo(fileToErrors.values.sumOf { it.size })

        if (expect.hasFailures()) {
            expect.withMessage(
                compilation.errors()
                    .sortedBy { it.lineNumber }
                    .joinToString(separator = "\n") {
                        "${it.lineNumber}: ${it.getMessage(Locale.ENGLISH)?.trim()}"
                    }
            ).fail()
        }
    }

    private fun classNotImmutableFailure(line: Long, className: String) =
        CompilationError(line = line, message = MessageUtils.classNotImmutableFailure(className))

    private fun nonInterfaceClassFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.nonInterfaceClassFailure())

    private fun nonInterfaceReturnFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.nonInterfaceReturnFailure())

    private fun nonInterfaceReturnFailure(line: Long, prefix: String = "", index: Int = -1) =
        CompilationError(
            line = line,
            message = MessageUtils.nonInterfaceReturnFailure(prefix = prefix, index = index)
        )

    private fun memberNotMethodFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.memberNotMethodFailure())

    private fun voidReturnFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.voidReturnFailure())

    private fun staticNonFinalFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.staticNonFinalFailure())

    private fun arrayFailure(line: Long) =
        CompilationError(line = line, message = MessageUtils.arrayFailure())

    private fun classNotFinalFailure(line: Long, className: String) =
        CompilationError(line = line, message = MessageUtils.classNotFinalFailure(className))

    data class CompilationError(
        val line: Long,
        val message: String,
    )
}

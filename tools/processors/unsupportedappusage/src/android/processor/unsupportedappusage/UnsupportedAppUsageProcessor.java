/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.processor.unsupportedappusage;

import static javax.tools.StandardLocation.CLASS_OUTPUT;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Pair;
import com.sun.tools.javac.util.Position;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor for {@link UnsupportedAppUsage} annotations.
 *
 * This processor currently outputs a CSV file with a mapping of dex signatures to corresponding
 * source positions.
 *
 * This is used for automating updates to the annotations themselves.
 */
@SupportedAnnotationTypes({"android.annotation.UnsupportedAppUsage",
        "dalvik.annotation.compat.UnsupportedAppUsage"
})
public class UnsupportedAppUsageProcessor extends AbstractProcessor {

    // Package name for writing output. Output will be written to the "class output" location within
    // this package.
    private static final String PACKAGE = "unsupportedappusage";
    private static final String INDEX_CSV = "unsupportedappusage_index.csv";

    private static final ImmutableSet<Class<? extends Annotation>> SUPPORTED_ANNOTATIONS =
            ImmutableSet.of(android.annotation.UnsupportedAppUsage.class,
                    dalvik.annotation.compat.UnsupportedAppUsage.class);
    private static final ImmutableSet<String> SUPPORTED_ANNOTATION_NAMES =
            SUPPORTED_ANNOTATIONS.stream().map(annotation -> annotation.getCanonicalName()).collect(
                    ImmutableSet.toImmutableSet());

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    /**
     * Write the contents of a stream to a text file, with one line per item.
     */
    private void writeToFile(String name,
            String headerLine,
            Stream<?> contents) throws IOException {
        PrintStream out = new PrintStream(processingEnv.getFiler().createResource(
                CLASS_OUTPUT,
                PACKAGE,
                name)
                .openOutputStream());
        out.println(headerLine);
        contents.forEach(o -> out.println(o));
        if (out.checkError()) {
            throw new IOException("Error when writing to " + name);
        }
        out.close();
    }

    /**
     * Find the annotation mirror for the @UnsupportedAppUsage annotation on the given element.
     */
    private AnnotationMirror getUnsupportedAppUsageAnnotationMirror(Element e) {
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) m.getAnnotationType().asElement();
            if (SUPPORTED_ANNOTATION_NAMES.contains(type.getQualifiedName().toString())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Returns a CSV header line for the columns returned by
     * {@link #getAnnotationIndex(String, Element)}.
     */
    private String getCsvHeaders() {
        return Joiner.on(',').join(
                "signature",
                "file",
                "startline",
                "startcol",
                "endline",
                "endcol",
                "properties"
        );
    }

    private String encodeAnnotationProperties(AnnotationMirror annotation) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : annotation.getElementValues().entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(e.getKey().getSimpleName())
                    .append("=")
                    .append(URLEncoder.encode(e.getValue().toString()));
        }
        return sb.toString();
    }

    /**
     * Maps an annotated element to the source position of the @UnsupportedAppUsage annotation
     * attached to it. It returns CSV in the format:
     * dex-signature,filename,start-line,start-col,end-line,end-col
     *
     * The positions refer to the annotation itself, *not* the annotated member. This can therefore
     * be used to read just the annotation from the file, and to perform in-place edits on it.
     *
     * @param signature        the dex signature for the element.
     * @param annotatedElement The annotated element
     * @return A single line of CSV text
     */
    private String getAnnotationIndex(String signature, Element annotatedElement) {
        JavacElements javacElem = (JavacElements) processingEnv.getElementUtils();
        AnnotationMirror unsupportedAppUsage =
                getUnsupportedAppUsageAnnotationMirror(annotatedElement);
        Pair<JCTree, JCTree.JCCompilationUnit> pair =
                javacElem.getTreeAndTopLevel(annotatedElement, unsupportedAppUsage, null);
        Position.LineMap lines = pair.snd.lineMap;
        return Joiner.on(",").join(
                signature,
                pair.snd.getSourceFile().getName(),
                lines.getLineNumber(pair.fst.pos().getStartPosition()),
                lines.getColumnNumber(pair.fst.pos().getStartPosition()),
                lines.getLineNumber(pair.fst.pos().getEndPosition(pair.snd.endPositions)),
                lines.getColumnNumber(pair.fst.pos().getEndPosition(pair.snd.endPositions)),
                encodeAnnotationProperties(unsupportedAppUsage));
    }

    /**
     * This is the main entry point in the processor, called by the compiler.
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, Element> signatureMap = new TreeMap<>();
        SignatureBuilder sb = new SignatureBuilder(processingEnv.getMessager());
        for (Class<? extends Annotation> supportedAnnotation : SUPPORTED_ANNOTATIONS) {
            Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(
                    supportedAnnotation);
            if (annotated.size() == 0) {
                continue;
            }
            // Build signatures for each annotated member and put them in a map from signature to
            // member.
            for (Element e : annotated) {
                String sig = sb.buildSignature(supportedAnnotation, e);
                if (sig != null) {
                    signatureMap.put(sig, e);
                }
            }
        }

        if (!signatureMap.isEmpty()) {
            try {
                writeToFile(INDEX_CSV,
                        getCsvHeaders(),
                        signatureMap.entrySet()
                                .stream()
                                .map(e -> getAnnotationIndex(e.getKey(), e.getValue())));
            } catch (IOException e) {
                throw new RuntimeException("Failed to write output", e);
            }
        }
        return true;
    }
}

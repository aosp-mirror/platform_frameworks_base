/*
 * Copyright 2019 The Android Open Source Project
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

package android.processor.view.inspector;

import static javax.tools.Diagnostic.Kind.ERROR;

import androidx.annotation.NonNull;

import com.squareup.javapoet.ClassName;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * An annotation processor for the platform inspectable annotations.
 *
 * It mostly delegates to {@link InspectablePropertyProcessor} and
 * {@link InspectionCompanionGenerator}. This modular architecture allows the core generation code
 * to be reused for comparable annotations outside the platform.
 *
 * @see android.view.inspector.InspectableProperty
 */
@SupportedAnnotationTypes({PlatformInspectableProcessor.ANNOTATION_QUALIFIED_NAME})
public final class PlatformInspectableProcessor extends AbstractProcessor {
    static final String ANNOTATION_QUALIFIED_NAME =
            "android.view.inspector.InspectableProperty";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(
            @NonNull Set<? extends TypeElement> annotations,
            @NonNull RoundEnvironment roundEnv) {
        final Map<String, InspectableClassModel> modelMap = new HashMap<>();

        for (TypeElement annotation : annotations) {
            if (annotation.getQualifiedName().contentEquals(ANNOTATION_QUALIFIED_NAME)) {
                processProperties(roundEnv.getElementsAnnotatedWith(annotation), modelMap);
            } else {
                fail("Unexpected annotation type", annotation);
            }
        }

        final InspectionCompanionGenerator generator =
                new InspectionCompanionGenerator(processingEnv.getFiler(), getClass());

        for (InspectableClassModel model : modelMap.values()) {
            try {
                generator.generate(model);
            } catch (IOException ioException) {
                fail(String.format(
                        "Unable to generate inspection companion for %s due to %s",
                        model.getClassName().toString(),
                        ioException.getMessage()));
            }
        }

        return true;
    }

    /**
     * Runs {@link PlatformInspectableProcessor} on a set of annotated elements.
     *
     * @param elements A set of annotated elements to process
     * @param modelMap A map of qualified class names to class models to update
     */
    private void processProperties(
            @NonNull Set<? extends Element> elements,
            @NonNull Map<String, InspectableClassModel> modelMap) {
        final InspectablePropertyProcessor processor =
                new InspectablePropertyProcessor(ANNOTATION_QUALIFIED_NAME, processingEnv);

        for (Element element : elements) {
            final Optional<TypeElement> classElement = enclosingClassElement(element);

            if (!classElement.isPresent()) {
                fail("Element not contained in a class", element);
                break;
            }

            final Set<Modifier> classModifiers = classElement.get().getModifiers();

            if (classModifiers.contains(Modifier.PRIVATE)) {
                fail("Enclosing class cannot be private", element);
            }

            final InspectableClassModel model = modelMap.computeIfAbsent(
                    classElement.get().getQualifiedName().toString(),
                    k -> {
                        if (hasNestedInspectionCompanion(classElement.get())) {
                            fail(
                                    String.format(
                                            "Class %s already has an inspection companion.",
                                            classElement.get().getQualifiedName().toString()),
                                    element);
                        }
                        return new InspectableClassModel(ClassName.get(classElement.get()));
                    });

            processor.process(element, model);
        }
    }

    /**
     * Determine if a class has a nested class named {@code InspectionCompanion}.
     *
     * @param typeElement A type element representing the class to check
     * @return f the class contains a class named {@code InspectionCompanion}
     */
    private static boolean hasNestedInspectionCompanion(@NonNull TypeElement typeElement) {
        for (TypeElement nestedClass : ElementFilter.typesIn(typeElement.getEnclosedElements())) {
            if (nestedClass.getSimpleName().toString().equals("InspectionCompanion")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get the nearest enclosing class if there is one.
     *
     * If {@param element} represents a class, it will be returned wrapped in an optional.
     *
     * @param element An element to search from
     * @return A TypeElement of the nearest enclosing class or an empty optional
     */
    @NonNull
    private static Optional<TypeElement> enclosingClassElement(@NonNull Element element) {
        Element cursor = element;

        while (cursor != null) {
            if (cursor.getKind() == ElementKind.CLASS) {
                return Optional.of((TypeElement) cursor);
            }

            cursor = cursor.getEnclosingElement();
        }

        return Optional.empty();
    }

    /**
     * Print message and fail the build.
     *
     * @param message Message to print
     */
    private void fail(@NonNull String message) {
        processingEnv.getMessager().printMessage(ERROR, message);
    }

    /**
     * Print message and fail the build.
     *
     * @param message Message to print
     * @param element The element that failed
     */
    private void fail(@NonNull String message, @NonNull Element element) {
        processingEnv.getMessager().printMessage(ERROR, message, element);
    }
}

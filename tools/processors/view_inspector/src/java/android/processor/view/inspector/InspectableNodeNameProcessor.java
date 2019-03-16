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

import androidx.annotation.NonNull;

import java.util.Optional;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

/**
 * Process {@code @InspectableNodeName} annotations.
 *
 * @see android.view.inspector.InspectableNodeName
 */
public final class InspectableNodeNameProcessor implements ModelProcessor {
    private final @NonNull String mQualifiedName;
    private final @NonNull ProcessingEnvironment mProcessingEnv;
    private final @NonNull AnnotationUtils mAnnotationUtils;

    /**
     * @param annotationQualifiedName The qualified name of the annotation to process
     * @param processingEnv The processing environment from the parent processor
     */
    public InspectableNodeNameProcessor(
            @NonNull String annotationQualifiedName,
            @NonNull ProcessingEnvironment processingEnv) {
        mQualifiedName = annotationQualifiedName;
        mProcessingEnv = processingEnv;
        mAnnotationUtils = new AnnotationUtils(processingEnv);
    }

    /**
     * Set the node name on the model if one is supplied.
     *
     * If the model already has a different node name, the node name will not be updated, and
     * the processor will print an error the the messager.
     *
     * @param element The annotated element to operate on
     * @param model The model this element should be merged into
     */
    @Override
    public void process(@NonNull Element element, @NonNull InspectableClassModel model) {
        try {
            final AnnotationMirror mirror =
                    mAnnotationUtils.exactlyOneMirror(mQualifiedName, element);
            final Optional<String> nodeName = mAnnotationUtils
                    .typedValueByName("value", String.class, element, mirror);

            if (!model.getNodeName().isPresent() || model.getNodeName().equals(nodeName)) {
                model.setNodeName(nodeName);
            } else {
                final String message = String.format(
                        "Node name was already set to \"%s\", refusing to change it to \"%s\".",
                        model.getNodeName().get(),
                        nodeName);
                throw new ProcessingException(message, element, mirror);
            }
        } catch (ProcessingException processingException) {
            processingException.print(mProcessingEnv.getMessager());
        }
    }
}

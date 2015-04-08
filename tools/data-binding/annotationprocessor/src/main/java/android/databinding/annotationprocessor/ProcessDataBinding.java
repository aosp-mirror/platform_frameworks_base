/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.annotationprocessor;

import android.databinding.BindingBuildInfo;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.writer.AnnotationJavaFileWriter;
import android.databinding.tool.writer.JavaFileWriter;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

@SupportedAnnotationTypes({
        "android.databinding.BindingAdapter",
        "android.databinding.Untaggable",
        "android.databinding.BindingMethods",
        "android.databinding.BindingConversion",
        "android.databinding.BindingBuildInfo"}
)
@SupportedSourceVersion(SourceVersion.RELEASE_7)
/**
 * Parent annotation processor that dispatches sub steps to ensure execution order.
 * Use initProcessingSteps to add a new step.
 */
public class ProcessDataBinding extends AbstractProcessor {
    private List<ProcessingStep> mProcessingSteps;
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mProcessingSteps == null) {
            initProcessingSteps();
        }
        final BindingBuildInfo buildInfo = BuildInfoUtil.load(roundEnv);
        if (buildInfo == null) {
            return false;
        }
        boolean done = true;
        for (ProcessingStep step : mProcessingSteps) {
            done = step.runStep(roundEnv, processingEnv, buildInfo) && done;
        }
        if (roundEnv.processingOver()) {
            for (ProcessingStep step : mProcessingSteps) {
                step.onProcessingOver(roundEnv, processingEnv, buildInfo);
            }
        }
        return done;
    }

    private void initProcessingSteps() {
        ProcessBindable processBindable = new ProcessBindable();
        mProcessingSteps = Arrays.asList(
                new ProcessMethodAdapters(),
                new ProcessExpressions(processBindable),
                processBindable
        );
        AnnotationJavaFileWriter javaFileWriter = new AnnotationJavaFileWriter(processingEnv);
        for (ProcessingStep step : mProcessingSteps) {
            step.mJavaFileWriter = javaFileWriter;
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        ModelAnalyzer.setProcessingEnvironment(processingEnv);
    }

    /**
     * To ensure execution order and binding build information, we use processing steps.
     */
    public abstract static class ProcessingStep {
        private boolean mDone;
        private JavaFileWriter mJavaFileWriter;

        protected JavaFileWriter getWriter() {
            return mJavaFileWriter;
        }

        private boolean runStep(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                BindingBuildInfo buildInfo) {
            if (mDone) {
                return true;
            }
            mDone = onHandleStep(roundEnvironment, processingEnvironment, buildInfo);
            return mDone;
        }

        /**
         * Invoked in each annotation processing step.
         *
         * @return True if it is done and should never be invoked again.
         */
        abstract public boolean onHandleStep(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                BindingBuildInfo buildInfo);

        /**
         * Invoked when processing is done. A good place to generate the output if the
         * processor requires multiple steps.
         */
        abstract public void onProcessingOver(RoundEnvironment roundEnvironment,
                ProcessingEnvironment processingEnvironment,
                BindingBuildInfo buildInfo);
    }
}

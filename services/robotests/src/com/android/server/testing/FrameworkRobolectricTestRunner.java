/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing;

import static java.util.Arrays.asList;

import com.google.common.collect.ImmutableSet;

import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.internal.SandboxFactory;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.SandboxClassLoader;
import org.robolectric.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

/**
 * HACK
 * Robolectric loads up Android environment from prebuilt android jars before running a method.
 * These jars are versioned according to the SDK level configured for the method (or class). The
 * jars represent a snapshot of the Android APIs in that SDK level. For Robolectric tests that are
 * testing Android components themselves we don't want certain classes (usually the
 * class-under-test) to be loaded from the prebuilt jar, we want it instead to be loaded from the
 * dependencies of our test target, i.e. the system class loader. That way we can write tests
 * against the actual classes that are in the tree, not a past version of them. Ideally we would
 * have a locally built jar referenced by Robolectric, but until that happens one can use this
 * class.
 * This class reads the {@link SystemLoaderClasses} or {@link SystemLoaderPackages} annotations on
 * test classes, for classes that match the annotations it will bypass the android jar and load it
 * from the system class loader. Allowing the test to test the actual class in the tree.
 *
 * Implementation note: One could think about overriding
 * {@link RobolectricTestRunner#createClassLoaderConfig(FrameworkMethod)} method and putting the
 * classes in the annotation in the {@link InstrumentationConfiguration} list of classes not to
 * acquire. Unfortunately, this will not work because we will not be instrumenting the class.
 * Instead, we have to load the class bytes from the system class loader but still instrument it, we
 * do this by overriding {@link SandboxClassLoader#getByteCode(String)} and loading the class bytes
 * from the system class loader if it in the {@link SystemLoaderClasses} annotation. This way the
 * {@link SandboxClassLoader} still instruments the class, but it's not loaded from the android jar.
 * Finally, we inject the custom class loader in place of the default one.
 *
 * TODO: Remove this when we are using locally built android jars in the method's environment.
 */
public class FrameworkRobolectricTestRunner extends RobolectricTestRunner {
    private final SandboxFactory mSandboxFactory;

    public FrameworkRobolectricTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
        Set<String> classPrefixes = getSystemLoaderClassPrefixes(testClass);
        mSandboxFactory = new FrameworkSandboxFactory(classPrefixes);
    }

    private Set<String> getSystemLoaderClassPrefixes(Class<?> testClass) {
        Set<String> classPrefixes = new HashSet<>();
        SystemLoaderClasses byClass = testClass.getAnnotation(SystemLoaderClasses.class);
        if (byClass != null) {
            Stream.of(byClass.value()).map(Class::getName).forEach(classPrefixes::add);
        }
        SystemLoaderPackages byPackage = testClass.getAnnotation(SystemLoaderPackages.class);
        if (byPackage != null) {
            classPrefixes.addAll(asList(byPackage.value()));
        }
        return classPrefixes;
    }

    @Nonnull
    @Override
    protected SdkEnvironment getSandbox(FrameworkMethod method) {
        // HACK: Calling super just to get SdkConfig via sandbox.getSdkConfig(), because
        // RobolectricFrameworkMethod, the runtime class of method, is package-protected
        SdkEnvironment sandbox = super.getSandbox(method);
        return mSandboxFactory.getSdkEnvironment(
                createClassLoaderConfig(method),
                getJarResolver(),
                sandbox.getSdkConfig());
    }

    private static class FrameworkClassLoader extends SandboxClassLoader {
        private final Set<String> mSystemLoaderClassPrefixes;

        private FrameworkClassLoader(
                Set<String> systemLoaderClassPrefixes,
                ClassLoader systemClassLoader,
                InstrumentationConfiguration instrumentationConfig,
                URL... urls) {
            super(systemClassLoader, instrumentationConfig, urls);
            mSystemLoaderClassPrefixes = systemLoaderClassPrefixes;
        }

        @Override
        protected byte[] getByteCode(String className) throws ClassNotFoundException {
            String classFileName = className.replace('.', '/') + ".class";
            if (shouldLoadFromSystemLoader(className)) {
                try (InputStream classByteStream = getResourceAsStream(classFileName)) {
                    if (classByteStream == null) {
                        throw new ClassNotFoundException(className);
                    }
                    return Util.readBytes(classByteStream);
                } catch (IOException e) {
                    throw new ClassNotFoundException(
                            "Couldn't load " + className + " from system class loader", e);
                }
            }
            return super.getByteCode(className);
        }

        /**
         * HACK^2
         * The framework Robolectric run configuration puts a prebuilt in front of us, so we try not
         * to load the class from there, if possible.
         */
        @Override
        public InputStream getResourceAsStream(String resource) {
            try {
                Enumeration<URL> urls = getResources(resource);
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    if (!url.toString().toLowerCase().contains("prebuilt")) {
                        return url.openStream();
                    }
                }
            } catch (IOException e) {
                // Fall through
            }
            return super.getResourceAsStream(resource);
        }

        /**
         * Classes like com.package.ClassName$InnerClass should also be loaded from the system class
         * loader, so we test if the classes in the annotation are prefixes of the class to load.
         */
        private boolean shouldLoadFromSystemLoader(String className) {
            for (String classPrefix : mSystemLoaderClassPrefixes) {
                if (className.startsWith(classPrefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class FrameworkSandboxFactory extends SandboxFactory {
        private final Set<String> mSystemLoaderClassPrefixes;

        private FrameworkSandboxFactory(Set<String> systemLoaderClassPrefixes) {
            mSystemLoaderClassPrefixes = systemLoaderClassPrefixes;
        }

        @Nonnull
        @Override
        public ClassLoader createClassLoader(
                InstrumentationConfiguration instrumentationConfig, URL... urls) {
            return new FrameworkClassLoader(
                    mSystemLoaderClassPrefixes,
                    ClassLoader.getSystemClassLoader(),
                    instrumentationConfig,
                    urls);
        }
    }
}

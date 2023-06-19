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

package com.android.server.notification;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

@RunWith(AndroidJUnit4.class)
public class NotificationVisitUrisTest extends UiServiceTestCase {

    private static final String TAG = "VisitUrisTest";

    // Methods that are known to add Uris that are *NOT* verified.
    // This list should be emptied! Items can be removed as bugs are fixed.
    private static final Multimap<Class<?>, String> KNOWN_BAD =
            ImmutableMultimap.<Class<?>, String>builder()
                    .put(Notification.WearableExtender.class, "addAction") // TODO: b/281044385
                    .put(Person.Builder.class, "setUri") // TODO: b/281044385
                    .put(RemoteViews.class, "setRemoteAdapter") // TODO: b/281044385
                    .build();

    // Types that we can't really produce. No methods receiving these parameters will be invoked.
    private static final ImmutableSet<Class<?>> UNUSABLE_TYPES =
            ImmutableSet.of(Consumer.class, IBinder.class, MediaSession.Token.class, Parcel.class,
                    PrintWriter.class, Resources.Theme.class, View.class,
                    LayoutInflater.Factory2.class);

    // Maximum number of times we allow generating the same class recursively.
    // E.g. new RemoteViews.addView(new RemoteViews()) but stop there.
    private static final int MAX_RECURSION = 2;

    // Number of times a method called addX(X) will be called.
    private static final int NUM_ADD_CALLS = 2;

    // Number of elements to put in a generated array, e.g. for calling setGloops(Gloop[] gloops).
    private static final int NUM_ELEMENTS_IN_ARRAY = 3;

    // Constructors that should be used to create instances of specific classes. Overrides scoring.
    private static final ImmutableMap<Class<?>, Constructor<?>> PREFERRED_CONSTRUCTORS;

    static {
        try {
            PREFERRED_CONSTRUCTORS = ImmutableMap.of(
                    Notification.Builder.class,
                    Notification.Builder.class.getConstructor(Context.class, String.class));

            EXCLUDED_SETTERS_OVERLOADS = ImmutableMultimap.<Class<?>, Method>builder()
                    .put(RemoteViews.class,
                            // b/245950570: Tries to connect to service and will crash.
                            RemoteViews.class.getMethod("setRemoteAdapter",
                                    int.class, Intent.class))
                    .build();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // Setters that shouldn't be called, for various reasons (but NOT because they are KNOWN_BAD).
    private static final Multimap<Class<?>, String> EXCLUDED_SETTERS =
            ImmutableMultimap.<Class<?>, String>builder()
                    // Handled by testAllStyles().
                    .put(Notification.Builder.class, "setStyle")
                    // Handled by testAllExtenders().
                    .put(Notification.Builder.class, "extend")
                    // Handled by testAllActionExtenders().
                    .put(Notification.Action.Builder.class, "extend")
                    // Overwrites icon supplied to constructor.
                    .put(Notification.BubbleMetadata.Builder.class, "setIcon")
                    // Discards previously-added actions.
                    .put(RemoteViews.class, "mergeRemoteViews")
                    .build();

    // Same as above, but specific overloads that should not be called.
    private static final Multimap<Class<?>, Method> EXCLUDED_SETTERS_OVERLOADS;

    private Context mContext;

    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test // This is a meta-test, checks that the generators are not broken.
    public void verifyTest() {
        Generated<Notification> notification = buildNotification(mContext,
                /* styleClass= */ Notification.MessagingStyle.class,
                /* extenderClass= */ Notification.WearableExtender.class,
                /* actionExtenderClass= */ Notification.Action.WearableExtender.class,
                /* includeRemoteViews= */ true);
        assertThat(notification.includedUris.size()).isAtLeast(730);
    }

    @Test
    public void testPlainNotification() throws Exception {
        Generated<Notification> notification = buildNotification(mContext, /* styleClass= */ null,
                /* extenderClass= */ null, /* actionExtenderClass= */ null,
                /* includeRemoteViews= */ false);
        verifyAllUrisAreVisited(notification.value, notification.includedUris,
                "Plain Notification");
    }

    @Test
    public void testRemoteViews() throws Exception {
        Generated<Notification> notification = buildNotification(mContext, /* styleClass= */ null,
                /* extenderClass= */ null, /* actionExtenderClass= */ null,
                /* includeRemoteViews= */ true);
        verifyAllUrisAreVisited(notification.value, notification.includedUris,
                "Notification with Remote Views");
    }

    @Test
    public void testAllStyles() throws Exception {
        for (Class<?> styleClass : ReflectionUtils.getConcreteSubclasses(Notification.Style.class,
                Notification.class)) {
            Generated<Notification> notification = buildNotification(mContext, styleClass,
                    /* extenderClass= */ null, /* actionExtenderClass= */ null,
                    /* includeRemoteViews= */ false);
            verifyAllUrisAreVisited(notification.value, notification.includedUris,
                    String.format("Style (%s)", styleClass.getSimpleName()));
        }
    }

    @Test
    public void testAllExtenders() throws Exception {
        for (Class<?> extenderClass : ReflectionUtils.getConcreteSubclasses(
                Notification.Extender.class, Notification.class)) {
            Generated<Notification> notification = buildNotification(mContext,
                    /* styleClass= */ null, extenderClass, /* actionExtenderClass= */ null,
                    /* includeRemoteViews= */ false);
            verifyAllUrisAreVisited(notification.value, notification.includedUris,
                    String.format("Extender (%s)", extenderClass.getSimpleName()));
        }
    }

    @Test
    public void testAllActionExtenders() throws Exception {
        for (Class<?> actionExtenderClass : ReflectionUtils.getConcreteSubclasses(
                Notification.Action.Extender.class, Notification.Action.class)) {
            Generated<Notification> notification = buildNotification(mContext,
                    /* styleClass= */ null, /* extenderClass= */ null, actionExtenderClass,
                    /* includeRemoteViews= */ false);
            verifyAllUrisAreVisited(notification.value, notification.includedUris,
                    String.format("Action.Extender (%s)", actionExtenderClass.getSimpleName()));
        }
    }

    private void verifyAllUrisAreVisited(Notification notification, List<Uri> includedUris,
            String notificationTypeMessage) throws Exception {
        Consumer<Uri> visitor = (Consumer<Uri>) Mockito.mock(Consumer.class);
        ArgumentCaptor<Uri> visitedUriCaptor = ArgumentCaptor.forClass(Uri.class);

        notification.visitUris(visitor);

        Mockito.verify(visitor, Mockito.atLeastOnce()).accept(visitedUriCaptor.capture());
        List<Uri> visitedUris = new ArrayList<>(visitedUriCaptor.getAllValues());
        visitedUris.remove(null);

        expect.withMessage(notificationTypeMessage)
                .that(visitedUris)
                .containsAtLeastElementsIn(includedUris);
        expect.that(KNOWN_BAD).isNotEmpty(); // Once empty, switch to containsExactlyElementsIn()
    }

    private static Generated<Notification> buildNotification(Context context,
            @Nullable Class<?> styleClass, @Nullable Class<?> extenderClass,
            @Nullable Class<?> actionExtenderClass, boolean includeRemoteViews) {
        SpecialParameterGenerator specialGenerator = new SpecialParameterGenerator(context);
        Set<Class<?>> excludedClasses = includeRemoteViews
                ? ImmutableSet.of()
                : ImmutableSet.of(RemoteViews.class);
        Location location = Location.root(Notification.Builder.class);

        Notification.Builder builder = new Notification.Builder(context, "channelId");
        invokeAllSetters(builder, location, /* allOverloads= */ false,
                /* includingVoidMethods= */ false, excludedClasses, specialGenerator);

        if (styleClass != null) {
            builder.setStyle((Notification.Style) generateObject(styleClass,
                    location.plus("setStyle", Notification.Style.class),
                    excludedClasses, specialGenerator));
        }
        if (extenderClass != null) {
            builder.extend((Notification.Extender) generateObject(extenderClass,
                    location.plus("extend", Notification.Extender.class),
                    excludedClasses, specialGenerator));
        }
        if (actionExtenderClass != null) {
            Location actionLocation = location.plus("addAction", Notification.Action.class);
            Notification.Action.Builder actionBuilder =
                    (Notification.Action.Builder) generateObject(
                            Notification.Action.Builder.class, actionLocation, excludedClasses,
                            specialGenerator);
            actionBuilder.extend((Notification.Action.Extender) generateObject(actionExtenderClass,
                    actionLocation.plus(
                            Notification.Action.Builder.class).plus("extend",
                            Notification.Action.Extender.class),
                    excludedClasses, specialGenerator));
            builder.addAction(actionBuilder.build());
        }

        return new Generated<>(builder.build(), specialGenerator.getGeneratedUris());
    }

    private static Object generateObject(Class<?> clazz, Location where,
            Set<Class<?>> excludingClasses, SpecialParameterGenerator specialGenerator) {
        if (excludingClasses.contains(clazz)) {
            throw new IllegalArgumentException(
                    String.format("Asked to generate a %s but it's part of the excluded set (%s)",
                            clazz, excludingClasses));
        }

        if (SpecialParameterGenerator.canGenerate(clazz)) {
            return specialGenerator.generate(clazz, where);
        }
        if (clazz.isEnum()) {
            return clazz.getEnumConstants()[0];
        }
        if (clazz.isArray()) {
            Object arrayValue = Array.newInstance(clazz.getComponentType(), NUM_ELEMENTS_IN_ARRAY);
            for (int i = 0; i < Array.getLength(arrayValue); i++) {
                Array.set(arrayValue, i,
                        generateObject(clazz.getComponentType(), where, excludingClasses,
                                specialGenerator));
            }
            return arrayValue;
        }

        Log.i(TAG, "About to generate a(n)" + clazz.getName());

        // Need to construct one of these. Look for a Builder inner class... and also look for a
        // Builder as a "sibling" class; CarExtender.UnreadConversation does this :(
        Stream<Class<?>> maybeBuilders =
                Stream.concat(Arrays.stream(clazz.getDeclaredClasses()),
                        clazz.getDeclaringClass() != null
                                ? Arrays.stream(clazz.getDeclaringClass().getDeclaredClasses())
                                : Stream.empty());
        Optional<Class<?>> clazzBuilder =
                maybeBuilders
                        .filter(maybeBuilder -> maybeBuilder.getSimpleName().equals("Builder"))
                        .filter(maybeBuilder ->
                                Arrays.stream(maybeBuilder.getMethods()).anyMatch(
                                        m -> m.getName().equals("build")
                                                && m.getParameterCount() == 0
                                                && m.getReturnType().equals(clazz)))
                        .findFirst();


        if (clazzBuilder.isPresent()) {
            try {
                // Found a Builder! Create an instance of it, call its setters, and call build()
                // on it.
                Object builder = constructEmpty(clazzBuilder.get(), where.plus(clazz),
                        excludingClasses, specialGenerator);
                invokeAllSetters(builder, where.plus(clazz).plus(clazzBuilder.get()),
                        /* allOverloads= */ false, /* includingVoidMethods= */ false,
                        excludingClasses, specialGenerator);

                Method buildMethod = builder.getClass().getMethod("build");
                Object built = buildMethod.invoke(builder);
                assertThat(built).isInstanceOf(clazz);
                return built;
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                        "Error using Builder " + clazzBuilder.get().getName(), e);
            }
        }

        // If no X.Builder, look for X() constructor.
        try {
            Object instance = constructEmpty(clazz, where, excludingClasses, specialGenerator);
            invokeAllSetters(instance, where.plus(clazz), /* allOverloads= */ false,
                    /* includingVoidMethods= */ false, excludingClasses, specialGenerator);
            return instance;
        } catch (Exception e) {
            throw new UnsupportedOperationException("Error generating a(n) " + clazz.getName(), e);
        }
    }

    private static Object constructEmpty(Class<?> clazz, Location where,
            Set<Class<?>> excludingClasses, SpecialParameterGenerator specialGenerator) {
        Constructor<?> bestConstructor;
        if (PREFERRED_CONSTRUCTORS.containsKey(clazz)) {
            // Use the preferred constructor.
            bestConstructor = PREFERRED_CONSTRUCTORS.get(clazz);
        } else if (Notification.Extender.class.isAssignableFrom(clazz)
                || Notification.Action.Extender.class.isAssignableFrom(clazz)) {
            // For extenders, prefer the empty constructors. The others are "partial-copy"
            // constructors and do not read all fields from the supplied Notification/Action.
            try {
                bestConstructor = clazz.getConstructor();
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                        String.format("Extender class %s doesn't have a zero-parameter constructor",
                                clazz.getName()));
            }
        } else {
            // Look for a non-deprecated constructor using any of the "interesting" parameters.
            List<Constructor<?>> allConstructors = Arrays.stream(clazz.getConstructors())
                    .filter(c -> c.getAnnotation(Deprecated.class) == null)
                    .collect(Collectors.toList());
            bestConstructor = ReflectionUtils.chooseBestOverload(allConstructors, where);
        }
        if (bestConstructor != null) {
            try {
                Object[] constructorParameters = generateParameters(bestConstructor,
                        where.plus(clazz), excludingClasses, specialGenerator);
                Log.i(TAG, "Invoking " + ReflectionUtils.methodToString(bestConstructor) + " with "
                        + Arrays.toString(constructorParameters));
                return bestConstructor.newInstance(constructorParameters);
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                        String.format("Error invoking constructor %s",
                                ReflectionUtils.methodToString(bestConstructor)), e);
            }
        }

        // Look for a "static constructor", i.e. some factory method on the same class.
        List<Method> factoryMethods = Arrays.stream(clazz.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && clazz.equals(m.getReturnType()))
                .collect(Collectors.toList());
        Method bestFactoryMethod = ReflectionUtils.chooseBestOverload(factoryMethods, where);
        if (bestFactoryMethod != null) {
            try {
                Object[] methodParameters = generateParameters(bestFactoryMethod, where.plus(clazz),
                        excludingClasses, specialGenerator);
                Log.i(TAG,
                        "Invoking " + ReflectionUtils.methodToString(bestFactoryMethod) + " with "
                                + Arrays.toString(methodParameters));
                return bestFactoryMethod.invoke(null, methodParameters);
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                        "Error invoking constructor-like static method "
                                + bestFactoryMethod.getName() + " for " + clazz.getName(), e);
            }
        }

        throw new UnsupportedOperationException(
                "Couldn't find a way to construct a(n) " + clazz.getName());
    }

    private static void invokeAllSetters(Object instance, Location where, boolean allOverloads,
            boolean includingVoidMethods, Set<Class<?>> excludingParameterTypes,
            SpecialParameterGenerator specialGenerator) {
        for (Method setter : ReflectionUtils.getAllSetters(instance.getClass(), where,
                allOverloads, includingVoidMethods, excludingParameterTypes)) {
            try {
                int numInvocations = setter.getName().startsWith("add") ? NUM_ADD_CALLS : 1;
                for (int i = 0; i < numInvocations; i++) {

                    // If the method is a "known bad" (i.e. adds Uris that aren't visited later)
                    // then still call it, but don't add to list of generated Uris. Easiest way is
                    // to use a throw-away SpecialParameterGenerator instead of the accumulating
                    // one.
                    SpecialParameterGenerator specialGeneratorForThisSetter =
                            KNOWN_BAD.containsEntry(instance.getClass(), setter.getName())
                                    ? new SpecialParameterGenerator(specialGenerator.mContext)
                                    : specialGenerator;

                    Object[] setterParam = generateParameters(setter, where,
                            excludingParameterTypes, specialGeneratorForThisSetter);
                    Log.i(TAG, "Invoking " + ReflectionUtils.methodToString(setter) + " with "
                            + setterParam[0]);
                    setter.invoke(instance, setterParam);
                }
            } catch (Exception e) {
                throw new UnsupportedOperationException(
                        "Error invoking setter " + ReflectionUtils.methodToString(setter), e);
            }
        }
    }

    private static Object[] generateParameters(Executable executable, Location where,
            Set<Class<?>> excludingClasses, SpecialParameterGenerator specialGenerator) {
        Log.i(TAG, "About to generate parameters for " + ReflectionUtils.methodToString(executable)
                + " in " + where);
        Class<?>[] parameterTypes = executable.getParameterTypes();
        Object[] parameterValues = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterValues[i] = generateObject(
                    parameterTypes[i],
                    where.plus(executable,
                            String.format("[%d,%s]", i, parameterTypes[i].getName())),
                    excludingClasses,
                    specialGenerator);
        }
        return parameterValues;
    }

    private static class ReflectionUtils {
        static Set<Class<?>> getConcreteSubclasses(Class<?> clazz, Class<?> containerClass) {
            return Arrays.stream(containerClass.getDeclaredClasses())
                    .filter(
                            innerClass -> clazz.isAssignableFrom(innerClass)
                                    && !Modifier.isAbstract(innerClass.getModifiers()))
                    .collect(Collectors.toSet());
        }

        static String methodToString(Executable executable) {
            return String.format("%s::%s(%s)",
                    executable.getDeclaringClass().getName(),
                    executable.getName(),
                    Arrays.stream(executable.getParameterTypes()).map(Class::getSimpleName)
                            .collect(Collectors.joining(", "))
            );
        }

        static List<Method> getAllSetters(Class<?> clazz, Location where, boolean allOverloads,
                boolean includingVoidMethods, Set<Class<?>> excludingParameterTypes) {
            ListMultimap<String, Method> methods = ArrayListMultimap.create();
            // Candidate "setters" are any methods that receive one at least parameter and are
            // either void (if acceptable) or return the same type being built.
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && method.getAnnotation(Deprecated.class) == null
                        && ((includingVoidMethods && method.getReturnType().equals(Void.TYPE))
                        || method.getReturnType().equals(clazz))
                        && method.getParameterCount() >= 1
                        && !EXCLUDED_SETTERS.containsEntry(clazz, method.getName())
                        && !EXCLUDED_SETTERS_OVERLOADS.containsEntry(clazz, method)
                        && Arrays.stream(method.getParameterTypes())
                            .noneMatch(excludingParameterTypes::contains)) {
                    methods.put(method.getName(), method);
                }
            }

            // In case of overloads, prefer those with the most interesting parameters.
            List<Method> setters = new ArrayList<>();
            for (String methodName : methods.keySet()) {
                setters.addAll(chooseOverloads(methods.get(methodName), where, allOverloads));
            }

            // Exclude set(x[]) when there exists add(x).
            List<Method> excludedSetters = setters.stream().filter(
                    m1 -> m1.getName().startsWith("set")
                            && setters.stream().anyMatch(
                                    m2 -> {
                                            Class<?> param1 = m1.getParameterTypes()[0];
                                            Class<?> param2 = m2.getParameterTypes()[0];
                                            return m2.getName().startsWith("add")
                                                    && param1.isArray()
                                                    && !param2.isArray() && !param2.isPrimitive()
                                                    && param1.getComponentType().equals(param2);
                                    })).toList();

            setters.removeAll(excludedSetters);
            return setters;
        }

        @Nullable
        static <T extends Executable> T chooseBestOverload(List<T> executables, Location where) {
            ImmutableList<T> chosen = chooseOverloads(executables, where,
                    /* chooseMultiple= */ false);
            return (chosen.isEmpty() ? null : chosen.get(0));
        }

        static <T extends Executable> ImmutableList<T> chooseOverloads(List<T> executables,
                Location where, boolean chooseMultiple) {
            // Exclude variants with non-usable parameters and too-deep recursions.
            executables = executables.stream()
                    .filter(e -> Arrays.stream(e.getParameterTypes()).noneMatch(
                            p -> UNUSABLE_TYPES.contains(p)
                                    || where.getClassOccurrenceCount(p) >= MAX_RECURSION))
                    .collect(Collectors.toList());

            if (executables.size() <= 1) {
                return ImmutableList.copyOf(executables);
            }

            // Overloads in "builders" usually set the same thing in two different ways (e.g.
            // x(Bitmap) and x(Icon)). We choose the one with the most "interesting" parameters
            // (from the point of view of containing Uris). In case of ties, LEAST parameters win,
            // to use the simplest.
            ArrayList<T> sortedCopy = new ArrayList<>(executables);
            sortedCopy.sort(
                    Comparator.comparingInt(ReflectionUtils::getMethodScore)
                            .thenComparing(Executable::getParameterCount)
                            .reversed());

            return chooseMultiple
                    ? ImmutableList.copyOf(sortedCopy)
                    : ImmutableList.of(sortedCopy.get(0));
        }

        /**
         * Counts the number of "interesting" parameters in a method. Used to choose the constructor
         * or builder-setter overload most suited to this test (e.g. prefer
         * {@link Notification.Builder#setLargeIcon(Icon)} to
         * {@link Notification.Builder#setLargeIcon(Bitmap)}.
         */
        static int getMethodScore(Executable executable) {
            return Arrays.stream(executable.getParameterTypes())
                    .mapToInt(SpecialParameterGenerator::getParameterScore).sum();
        }
    }

    private static class SpecialParameterGenerator {
        private static final ImmutableSet<Class<?>> INTERESTING_CLASSES =
                ImmutableSet.of(
                        Person.class, Uri.class, Icon.class, Intent.class, PendingIntent.class,
                        RemoteViews.class);
        private static final ImmutableSet<Class<?>> MOCKED_CLASSES = ImmutableSet.of();

        private static final ImmutableMap<Class<?>, Object> PRIMITIVE_VALUES =
                ImmutableMap.<Class<?>, Object>builder()
                        .put(boolean.class, false)
                        .put(byte.class, (byte) 4)
                        .put(short.class, (short) 44)
                        .put(int.class, 1)
                        .put(long.class, 44444444L)
                        .put(float.class, 33.33f)
                        .put(double.class, 3333.3333d)
                        .put(char.class, 'N')
                        .build();

        private final Context mContext;
        private final List<Uri> mGeneratedUris = new ArrayList<>();
        private int mNextUriCounter = 1;

        SpecialParameterGenerator(Context context) {
            mContext = context;
        }

        static boolean canGenerate(Class<?> clazz) {
            return (INTERESTING_CLASSES.contains(clazz) && !clazz.equals(Person.class))
                    || MOCKED_CLASSES.contains(clazz)
                    || clazz.equals(Context.class)
                    || clazz.equals(Bundle.class)
                    || clazz.equals(Bitmap.class)
                    || clazz.isPrimitive()
                    || clazz.equals(CharSequence.class) || clazz.equals(String.class);
        }

        static int getParameterScore(Class<?> parameterClazz) {
            if (parameterClazz.isArray()) {
                return getParameterScore(parameterClazz.getComponentType());
            } else if (INTERESTING_CLASSES.contains(parameterClazz)) {
                return 10;
            } else if (parameterClazz.isPrimitive() || parameterClazz.equals(CharSequence.class)
                    || parameterClazz.equals(String.class)) {
                return 0;
            } else {
                // No idea. We don't deep inspect, but score them as better than known-useless.
                return 1;
            }
        }

        Object generate(Class<?> clazz, Location where) {
            if (clazz == Uri.class) {
                return generateUri(where);
            }

            // Interesting parameters
            if (clazz == Icon.class) {
                Uri iconUri = generateUri(
                        where.plus(Icon.class).plus("createWithContentUri", Uri.class));
                return Icon.createWithContentUri(iconUri);
            }

            if (clazz == Intent.class) {
                // TODO(b/281044385): Are Intent Uris (new Intent(String,Uri)) relevant?
                return new Intent("action");
            }

            if (clazz == PendingIntent.class) {
                // PendingIntent can have an Intent with a Uri but those are inaccessible and
                // not inspected.
                return PendingIntent.getActivity(mContext, 0, new Intent("action"),
                        PendingIntent.FLAG_IMMUTABLE);
            }

            if (clazz == RemoteViews.class) {
                RemoteViews rv = new RemoteViews(mContext.getPackageName(), /* layoutId= */ 10);
                invokeAllSetters(rv, where.plus(RemoteViews.class),
                        /* allOverloads= */ true, /* includingVoidMethods= */ true,
                        /* excludingParameterTypes= */ ImmutableSet.of(), this);
                return rv;
            }

            if (MOCKED_CLASSES.contains(clazz)) {
                return Mockito.mock(clazz);
            }
            if (clazz.equals(Context.class)) {
                return mContext;
            }
            if (clazz.equals(Bundle.class)) {
                return new Bundle();
            }
            if (clazz.equals(Bitmap.class)) {
                return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
            }

            // ~Primitives
            if (PRIMITIVE_VALUES.containsKey(clazz)) {
                return PRIMITIVE_VALUES.get(clazz);
            }
            if (clazz.equals(CharSequence.class) || clazz.equals(String.class)) {
                return where + "->string";
            }

            throw new IllegalArgumentException(
                    "I have no idea how to produce a(n) " + clazz + ", sorry");
        }

        private Uri generateUri(Location where) {
            Uri uri = Uri.parse(String.format("%s - %s", mNextUriCounter++, where));
            mGeneratedUris.add(uri);
            return uri;
        }

        public List<Uri> getGeneratedUris() {
            return mGeneratedUris;
        }
    }

    private static class Location {

        private static class Item {
            @Nullable private final Class<?> mMaybeClass;
            @Nullable private final Executable mMaybeMethod;
            @Nullable private final String mExtra;

            Item(@NonNull Class<?> clazz) {
                mMaybeClass = checkNotNull(clazz);
                mMaybeMethod = null;
                mExtra = null;
            }

            Item(@NonNull Executable executable, @Nullable String extra) {
                mMaybeClass = null;
                mMaybeMethod = checkNotNull(executable);
                mExtra = extra;
            }

            @NonNull
            @Override
            public String toString() {
                String name = mMaybeClass != null
                        ? "CLASS:" + mMaybeClass.getName()
                        : "METHOD:" + mMaybeMethod.getName() + "/"
                                + mMaybeMethod.getParameterCount();
                return name + Strings.nullToEmpty(mExtra);
            }
        }

        private final ImmutableList<Item> mComponents;

        private Location(Iterable<Item> components) {
            mComponents = ImmutableList.copyOf(components);
        }

        private Location(Location soFar, Item next) {
            // Verify the class->method->class->method ordering.
            if (!soFar.mComponents.isEmpty()) {
                Item previous = soFar.getLastItem();
                if (previous.mMaybeMethod != null && next.mMaybeMethod != null) {
                    throw new IllegalArgumentException(
                            String.format("Unexpected sequence: %s ===> %s", soFar, next));
                }
            }
            mComponents = ImmutableList.<Item>builder().addAll(soFar.mComponents).add(next).build();
        }

        public static Location root(Class<?> clazz) {
            return new Location(ImmutableList.of(new Item(clazz)));
        }

        Location plus(Class<?> clazz) {
            return new Location(this, new Item(clazz));
        }

        Location plus(Executable executable, String extra) {
            return new Location(this, new Item(executable, extra));
        }

        public Location plus(String methodName, Class<?>... methodParameters) {
            Item lastClass = getLastItem();
            try {
                checkNotNull(lastClass.mMaybeClass, "Last item is not a class but %s", lastClass);
                Method method = lastClass.mMaybeClass.getMethod(methodName, methodParameters);
                return new Location(this, new Item(method, null));
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        String.format("Method %s not found in class %s",
                                methodName, lastClass.mMaybeClass.getName()));
            }
        }

        Item getLastItem() {
            checkState(!mComponents.isEmpty());
            return mComponents.get(mComponents.size() - 1);
        }

        @NonNull
        @Override
        public String toString() {
            return mComponents.stream().map(Item::toString).collect(Collectors.joining(" -> "));
        }

        public long getClassOccurrenceCount(Class<?> clazz) {
            return mComponents.stream().filter(c -> clazz.equals(c.mMaybeClass)).count();
        }
    }

    private static class Generated<T> {
        public final T value;
        public final ImmutableList<Uri> includedUris;

        private Generated(T value, Iterable<Uri> includedUris) {
            this.value = value;
            this.includedUris = ImmutableList.copyOf(includedUris);
        }
    }
}

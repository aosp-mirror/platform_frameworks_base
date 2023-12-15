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

package android.testing;

import android.testing.TestableLooper.LooperFrameworkMethod;
import android.testing.TestableLooper.RunWithLooper;

import org.junit.rules.MethodRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/*
 * This rule is meant to be an alternative of using AndroidTestingRunner.
 * It let tests to start from background thread, and assigns mainLooper or new
 * Looper for the Statement.
 */
public class TestWithLooperRule implements MethodRule {

    /*
     * This rule requires to be the inner most Rule, so the next statement is RunAfters
     * instead of another rule. You can set it by '@Rule(order = Integer.MAX_VALUE)'
     */
    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        // getting testRunner check, if AndroidTestingRunning then we skip this rule
        RunWith runWithAnnotation = target.getClass().getAnnotation(RunWith.class);
        if (runWithAnnotation != null) {
            // if AndroidTestingRunner or it's subclass is in use, do nothing
            if (AndroidTestingRunner.class.isAssignableFrom(runWithAnnotation.value())) {
                return base;
            }
        }

        // check if RunWithLooper annotation is used. If not skip this rule
        RunWithLooper looperAnnotation = method.getAnnotation(RunWithLooper.class);
        if (looperAnnotation == null) {
            looperAnnotation = target.getClass().getAnnotation(RunWithLooper.class);
        }
        if (looperAnnotation == null) {
            return base;
        }

        try {
            wrapMethodInStatement(base, method, target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return base;
    }

    // This method is based on JUnit4 test runner flow. It might need to be revisited when JUnit is
    // upgraded
    // TODO(b/277743626): use a cleaner way to wrap each statements; may require some JUnit
    //  patching to facilitate this.
    private void wrapMethodInStatement(Statement base, FrameworkMethod method, Object target)
            throws Exception {
        Statement next = base;
        try {
            while (next != null) {
                switch (next.getClass().getSimpleName()) {
                    case "RunAfters":
                        this.wrapFieldMethodFor(next, "afters", method, target);
                        next = getNextStatement(next, "next");
                        break;
                    case "RunBefores":
                        this.wrapFieldMethodFor(next, "befores", method, target);
                        next = getNextStatement(next, "next");
                        break;
                    case "FailOnTimeout":
                        // Note: withPotentialTimeout() from BlockJUnit4ClassRunner might use
                        // FailOnTimeout which always wraps a new thread during InvokeMethod
                        // method evaluation.
                        next = getNextStatement(next, "originalStatement");
                        break;
                    case "InvokeMethod":
                        this.wrapFieldMethodFor(next, "testMethod", method, target);
                        return;
                    case "InvokeParameterizedMethod":
                        this.wrapFieldMethodFor(next, "frameworkMethod", method, target);
                        return;
                    default:
                        throw new Exception(
                                String.format("Unexpected Statement received: [%s]",
                                next.getClass().getName())
                        );
                }
            }
        } catch (Exception e) {
            throw e;
        }
    }

    // Wrapping the befores, afters, and InvokeMethods with LooperFrameworkMethod
    // within the statement.
    private void wrapFieldMethodFor(Statement base, String fieldStr, FrameworkMethod method,
            Object target) throws NoSuchFieldException, IllegalAccessException {
        Field field = base.getClass().getDeclaredField(fieldStr);
        field.setAccessible(true);
        Object fieldInstance = field.get(base);
        if (fieldInstance instanceof FrameworkMethod) {
            field.set(base, looperWrap(method, target, (FrameworkMethod) fieldInstance));
        } else {
            // Befores and afters methods lists
            field.set(base, looperWrap(method, target, (List<FrameworkMethod>) fieldInstance));
        }
    }

    // Retrieve the next wrapped statement based on the selected field string
    private Statement getNextStatement(Statement base, String fieldStr)
            throws NoSuchFieldException, IllegalAccessException {
        Field nextField = base.getClass().getDeclaredField(fieldStr);
        nextField.setAccessible(true);
        Object value = nextField.get(base);
        return value instanceof Statement ? (Statement) value : null;
    }

    protected FrameworkMethod looperWrap(FrameworkMethod method, Object test,
            FrameworkMethod base) {
        RunWithLooper annotation = method.getAnnotation(RunWithLooper.class);
        if (annotation == null) annotation = test.getClass().getAnnotation(RunWithLooper.class);
        if (annotation != null) {
            return LooperFrameworkMethod.get(base, annotation.setAsMainLooper(), test);
        }
        return base;
    }

    protected List<FrameworkMethod> looperWrap(FrameworkMethod method, Object test,
            List<FrameworkMethod> methods) {
        RunWithLooper annotation = method.getAnnotation(RunWithLooper.class);
        if (annotation == null) annotation = test.getClass().getAnnotation(RunWithLooper.class);
        if (annotation != null) {
            methods = new ArrayList<>(methods);
            for (int i = 0; i < methods.size(); i++) {
                methods.set(i, LooperFrameworkMethod.get(methods.get(i),
                        annotation.setAsMainLooper(), test));
            }
        }
        return methods;
    }
}

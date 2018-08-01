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

package com.android.server.wm.flicker;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.Nullable;

import com.android.server.wm.flicker.Assertions.Result;
import com.android.server.wm.flicker.TransitionRunner.TransitionResult;

import com.google.common.truth.FailureStrategy;
import com.google.common.truth.Subject;
import com.google.common.truth.SubjectFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Truth subject for {@link WindowManagerTrace} objects.
 */
public class WmTraceSubject extends Subject<WmTraceSubject, WindowManagerTrace> {
    // Boiler-plate Subject.Factory for WmTraceSubject
    private static final SubjectFactory<WmTraceSubject, WindowManagerTrace> FACTORY =
            new SubjectFactory<WmTraceSubject, WindowManagerTrace>() {
                @Override
                public WmTraceSubject getSubject(
                        FailureStrategy fs, @Nullable WindowManagerTrace target) {
                    return new WmTraceSubject(fs, target);
                }
            };

    private AssertionsChecker<WindowManagerTrace.Entry> mChecker = new AssertionsChecker<>();

    private WmTraceSubject(FailureStrategy fs, @Nullable WindowManagerTrace subject) {
        super(fs, subject);
    }

    // User-defined entry point
    public static WmTraceSubject assertThat(@Nullable WindowManagerTrace entry) {
        return assertAbout(FACTORY).that(entry);
    }

    // User-defined entry point
    public static WmTraceSubject assertThat(@Nullable TransitionResult result) {
        WindowManagerTrace entries = WindowManagerTrace.parseFrom(result.getWindowManagerTrace(),
                result.getWindowManagerTracePath());
        return assertWithMessage(result.toString()).about(FACTORY).that(entries);
    }

    // Static method for getting the subject factory (for use with assertAbout())
    public static SubjectFactory<WmTraceSubject, WindowManagerTrace> entries() {
        return FACTORY;
    }

    public void forAllEntries() {
        test();
    }

    public void forRange(long startTime, long endTime) {
        mChecker.filterByRange(startTime, endTime);
        test();
    }

    public WmTraceSubject then() {
        mChecker.checkChangingAssertions();
        return this;
    }

    public void inTheBeginning() {
        if (getSubject().getEntries().isEmpty()) {
            fail("No entries found.");
        }
        mChecker.checkFirstEntry();
        test();
    }

    public void atTheEnd() {
        if (getSubject().getEntries().isEmpty()) {
            fail("No entries found.");
        }
        mChecker.checkLastEntry();
        test();
    }

    private void test() {
        List<Result> failures = mChecker.test(getSubject().getEntries());
        if (!failures.isEmpty()) {
            Optional<Path> failureTracePath = getSubject().getSource();
            String failureLogs = failures.stream().map(Result::toString)
                    .collect(Collectors.joining("\n"));
            String tracePath = "";
            if (failureTracePath.isPresent()) {
                tracePath = "\nWindowManager Trace can be found in: "
                        + failureTracePath.get().toAbsolutePath() + "\n";
            }
            fail(tracePath + failureLogs);
        }
    }

    public WmTraceSubject showsAboveAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isAboveAppWindowVisible(partialWindowTitle),
                "showsAboveAppWindow(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject hidesAboveAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isAboveAppWindowVisible(partialWindowTitle).negate(),
                "hidesAboveAppWindow" + "(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject showsBelowAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isBelowAppWindowVisible(partialWindowTitle),
                "showsBelowAppWindow(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject hidesBelowAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isBelowAppWindowVisible(partialWindowTitle).negate(),
                "hidesBelowAppWindow" + "(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject showsImeWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isImeWindowVisible(partialWindowTitle),
                "showsBelowAppWindow(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject hidesImeWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isImeWindowVisible(partialWindowTitle).negate(),
                "hidesImeWindow" + "(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject showsAppWindowOnTop(String partialWindowTitle) {
        mChecker.add(
                entry -> {
                    Result result = entry.isAppWindowVisible(partialWindowTitle);
                    if (result.passed()) {
                        result = entry.isVisibleAppWindowOnTop(partialWindowTitle);
                    }
                    return result;
                },
                "showsAppWindowOnTop(" + partialWindowTitle + ")"
        );
        return this;
    }

    public WmTraceSubject hidesAppWindowOnTop(String partialWindowTitle) {
        mChecker.add(
                entry -> {
                    Result result = entry.isAppWindowVisible(partialWindowTitle).negate();
                    if (result.failed()) {
                        result = entry.isVisibleAppWindowOnTop(partialWindowTitle).negate();
                    }
                    return result;
                },
                "hidesAppWindowOnTop(" + partialWindowTitle + ")"
        );
        return this;
    }

    public WmTraceSubject showsAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isAppWindowVisible(partialWindowTitle),
                "showsAppWindow(" + partialWindowTitle + ")");
        return this;
    }

    public WmTraceSubject hidesAppWindow(String partialWindowTitle) {
        mChecker.add(entry -> entry.isAppWindowVisible(partialWindowTitle).negate(),
                "hidesAppWindow(" + partialWindowTitle + ")");
        return this;
    }
}

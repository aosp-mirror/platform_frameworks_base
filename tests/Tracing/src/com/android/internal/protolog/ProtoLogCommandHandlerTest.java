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

package com.android.internal.protolog;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Test class for {@link ProtoLogImpl}.
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class ProtoLogCommandHandlerTest {

    @Mock
    ProtoLogConfigurationService mProtoLogConfigurationService;
    @Mock
    PrintWriter mPrintWriter;
    @Mock
    Binder mMockBinder;

    @Test
    public void printsHelpForAllAvailableCommands() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.onHelp();
        validateOnHelpPrinted();
    }

    @Test
    public void printsHelpIfCommandIsNull() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.onCommand(null);
        validateOnHelpPrinted();
    }

    @Test
    public void handlesGroupListCommand() {
        Mockito.when(mProtoLogConfigurationService.getGroups())
                .thenReturn(new String[] {"MY_TEST_GROUP", "MY_OTHER_GROUP"});
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "groups", "list" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("MY_TEST_GROUP"));
        Mockito.verify(mPrintWriter, times(1))
                .println(contains("MY_OTHER_GROUP"));
    }

    @Test
    public void handlesIncompleteGroupsCommand() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "groups" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("Incomplete command"));
    }

    @Test
    public void handlesGroupStatusCommand() {
        Mockito.when(mProtoLogConfigurationService.getGroups())
                .thenReturn(new String[] {"MY_GROUP"});
        Mockito.when(mProtoLogConfigurationService.isLoggingToLogcat("MY_GROUP")).thenReturn(true);
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "groups", "status", "MY_GROUP" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("MY_GROUP"));
        Mockito.verify(mPrintWriter, times(1))
                .println(contains("LOG_TO_LOGCAT = true"));
    }

    @Test
    public void handlesGroupStatusCommandOfUnregisteredGroups() {
        Mockito.when(mProtoLogConfigurationService.getGroups()).thenReturn(new String[] {});
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "groups", "status", "MY_GROUP" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("MY_GROUP"));
        Mockito.verify(mPrintWriter, times(1))
                .println(contains("UNREGISTERED"));
    }

    @Test
    public void handlesGroupStatusCommandWithNoGroups() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "groups", "status" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("Incomplete command"));
    }

    @Test
    public void handlesIncompleteLogcatCommand() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "logcat" });

        Mockito.verify(mPrintWriter, times(1))
                .println(contains("Incomplete command"));
    }

    @Test
    public void handlesLogcatEnableCommand() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "logcat", "enable", "MY_GROUP" });
        Mockito.verify(mProtoLogConfigurationService).enableProtoLogToLogcat("MY_GROUP");

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err,
                new String[] { "logcat", "enable", "MY_GROUP", "MY_OTHER_GROUP" });
        Mockito.verify(mProtoLogConfigurationService)
                .enableProtoLogToLogcat("MY_GROUP", "MY_OTHER_GROUP");
    }

    @Test
    public void handlesLogcatDisableCommand() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "logcat", "disable", "MY_GROUP" });
        Mockito.verify(mProtoLogConfigurationService).disableProtoLogToLogcat("MY_GROUP");

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err,
                new String[] { "logcat", "disable", "MY_GROUP", "MY_OTHER_GROUP" });
        Mockito.verify(mProtoLogConfigurationService)
                .disableProtoLogToLogcat("MY_GROUP", "MY_OTHER_GROUP");
    }

    @Test
    public void handlesLogcatEnableCommandWithNoGroups() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "logcat", "enable" });
        Mockito.verify(mPrintWriter).println(contains("Incomplete command"));
    }

    @Test
    public void handlesLogcatDisableCommandWithNoGroups() {
        final ProtoLogCommandHandler cmdHandler =
                new ProtoLogCommandHandler(mProtoLogConfigurationService, mPrintWriter);

        cmdHandler.exec(mMockBinder, FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, new String[] { "logcat", "disable" });
        Mockito.verify(mPrintWriter).println(contains("Incomplete command"));
    }

    private void validateOnHelpPrinted() {
        Mockito.verify(mPrintWriter, times(1)).println(endsWith("help"));
        Mockito.verify(mPrintWriter, times(1))
                .println(endsWith("groups (list | status)"));
        Mockito.verify(mPrintWriter, times(1))
                .println(endsWith("logcat (enable | disable) <group>"));
        Mockito.verify(mPrintWriter, atLeast(0)).println(anyString());
    }
}

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

package com.android.preload.classdataretrieval.jdwp;

import com.android.ddmlib.Client;
import com.android.preload.classdataretrieval.ClassDataRetriever;

import org.apache.harmony.jpda.tests.framework.jdwp.CommandPacket;
import org.apache.harmony.jpda.tests.framework.jdwp.JDWPCommands;
import org.apache.harmony.jpda.tests.framework.jdwp.JDWPConstants;
import org.apache.harmony.jpda.tests.framework.jdwp.ReplyPacket;
import org.apache.harmony.jpda.tests.jdwp.share.JDWPTestCase;
import org.apache.harmony.jpda.tests.jdwp.share.JDWPUnitDebuggeeWrapper;
import org.apache.harmony.jpda.tests.share.JPDALogWriter;
import org.apache.harmony.jpda.tests.share.JPDATestOptions;

import java.util.HashMap;
import java.util.Map;

public class JDWPClassDataRetriever extends JDWPTestCase implements ClassDataRetriever {

    private final Client client;

    public JDWPClassDataRetriever() {
        this(null);
    }

    public JDWPClassDataRetriever(Client client) {
        this.client = client;
    }


    @Override
    protected String getDebuggeeClassName() {
        return "<unset>";
    }

    @Override
    public Map<String, String> getClassData(Client client) {
        return new JDWPClassDataRetriever(client).retrieve();
    }

    private Map<String, String> retrieve() {
        if (client == null) {
            throw new IllegalStateException();
        }

        settings = createTestOptions("localhost:" + String.valueOf(client.getDebuggerListenPort()));
        settings.setDebuggeeSuspend("n");

        logWriter = new JPDALogWriter(System.out, "", false);

        try {
            internalSetUp();

            return retrieveImpl();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            internalTearDown();
        }
    }

    private Map<String, String> retrieveImpl() {
        try {
            // Suspend the app.
            {
                CommandPacket packet = new CommandPacket(
                        JDWPCommands.VirtualMachineCommandSet.CommandSetID,
                        JDWPCommands.VirtualMachineCommandSet.SuspendCommand);
                ReplyPacket reply = debuggeeWrapper.vmMirror.performCommand(packet);
                if (reply.getErrorCode() != JDWPConstants.Error.NONE) {
                    return null;
                }
            }

            // List all classes.
            CommandPacket packet = new CommandPacket(
                    JDWPCommands.VirtualMachineCommandSet.CommandSetID,
                    JDWPCommands.VirtualMachineCommandSet.AllClassesCommand);
            ReplyPacket reply = debuggeeWrapper.vmMirror.performCommand(packet);

            if (reply.getErrorCode() != JDWPConstants.Error.NONE) {
                return null;
            }

            int classCount = reply.getNextValueAsInt();
            System.out.println("Runtime reported " + classCount + " classes.");

            Map<Long, String> classes = new HashMap<Long, String>();
            Map<Long, String> arrayClasses = new HashMap<Long, String>();

            for (int i = 0; i < classCount; i++) {
                byte refTypeTag = reply.getNextValueAsByte();
                long typeID = reply.getNextValueAsReferenceTypeID();
                String signature = reply.getNextValueAsString();
                /* int status = */ reply.getNextValueAsInt();

                switch (refTypeTag) {
                    case JDWPConstants.TypeTag.CLASS:
                    case JDWPConstants.TypeTag.INTERFACE:
                        classes.put(typeID, signature);
                        break;

                    case JDWPConstants.TypeTag.ARRAY:
                        arrayClasses.put(typeID, signature);
                        break;
                }
            }

            Map<String, String> result = new HashMap<String, String>();

            // Parse all classes.
            for (Map.Entry<Long, String> entry : classes.entrySet()) {
                long typeID = entry.getKey();
                String signature = entry.getValue();

                if (!checkClass(typeID, signature, result)) {
                    System.err.println("Issue investigating " + signature);
                }
            }

            // For arrays, look at the leaf component type.
            for (Map.Entry<Long, String> entry : arrayClasses.entrySet()) {
                long typeID = entry.getKey();
                String signature = entry.getValue();

                if (!checkArrayClass(typeID, signature, result)) {
                    System.err.println("Issue investigating " + signature);
                }
            }

            return result;
        } finally {
            // Resume the app.
            {
                CommandPacket packet = new CommandPacket(
                        JDWPCommands.VirtualMachineCommandSet.CommandSetID,
                        JDWPCommands.VirtualMachineCommandSet.ResumeCommand);
                /* ReplyPacket reply = */ debuggeeWrapper.vmMirror.performCommand(packet);
            }
        }
    }

    private boolean checkClass(long typeID, String signature, Map<String, String> result) {
        CommandPacket packet = new CommandPacket(
                JDWPCommands.ReferenceTypeCommandSet.CommandSetID,
                JDWPCommands.ReferenceTypeCommandSet.ClassLoaderCommand);
        packet.setNextValueAsReferenceTypeID(typeID);
        ReplyPacket reply = debuggeeWrapper.vmMirror.performCommand(packet);
        if (reply.getErrorCode() != JDWPConstants.Error.NONE) {
            return false;
        }

        long classLoaderID = reply.getNextValueAsObjectID();

        // TODO: Investigate the classloader to have a better string?
        String classLoaderString = (classLoaderID == 0) ? null : String.valueOf(classLoaderID);

        result.put(getClassName(signature), classLoaderString);

        return true;
    }

    private boolean checkArrayClass(long typeID, String signature, Map<String, String> result) {
        // Classloaders of array classes are the same as the component class'.
        CommandPacket packet = new CommandPacket(
                JDWPCommands.ReferenceTypeCommandSet.CommandSetID,
                JDWPCommands.ReferenceTypeCommandSet.ClassLoaderCommand);
        packet.setNextValueAsReferenceTypeID(typeID);
        ReplyPacket reply = debuggeeWrapper.vmMirror.performCommand(packet);
        if (reply.getErrorCode() != JDWPConstants.Error.NONE) {
            return false;
        }

        long classLoaderID = reply.getNextValueAsObjectID();

        // TODO: Investigate the classloader to have a better string?
        String classLoaderString = (classLoaderID == 0) ? null : String.valueOf(classLoaderID);

        // For array classes, we *need* the signature directly.
        result.put(signature, classLoaderString);

        return true;
    }

    private static String getClassName(String signature) {
        String withoutLAndSemicolon = signature.substring(1, signature.length() - 1);
        return withoutLAndSemicolon.replace('/', '.');
    }


    private static JPDATestOptions createTestOptions(String address) {
        JPDATestOptions options = new JPDATestOptions();
        options.setAttachConnectorKind();
        options.setTimeout(1000);
        options.setWaitingTime(1000);
        options.setTransportAddress(address);
        return options;
    }

    @Override
    protected JDWPUnitDebuggeeWrapper createDebuggeeWrapper() {
        return new PreloadDebugeeWrapper(settings, logWriter);
    }
}

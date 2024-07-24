/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.ddm;

import android.util.Log;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Handle various requests related to profiling / debugging of the view system.
 * Support for these features are advertised via {@link DdmHandleHello}.
 */
public class DdmHandleViewDebug extends DdmHandle {
    /** List {@link ViewRootImpl}'s of this process. */
    private static final int CHUNK_VULW = ChunkHandler.type("VULW");

    /** Operation on view root, first parameter in packet should be one of VURT_* constants */
    private static final int CHUNK_VURT = ChunkHandler.type("VURT");

    /** Dump view hierarchy. */
    private static final int VURT_DUMP_HIERARCHY = 1;

    /** Capture View Layers. */
    private static final int VURT_CAPTURE_LAYERS = 2;

    /** Dump View Theme. */
    private static final int VURT_DUMP_THEME = 3;

    /**
     * Generic View Operation, first parameter in the packet should be one of the
     * VUOP_* constants below.
     */
    private static final int CHUNK_VUOP = ChunkHandler.type("VUOP");

    /** Capture View. */
    private static final int VUOP_CAPTURE_VIEW = 1;

    /** Obtain the Display List corresponding to the view. */
    private static final int VUOP_DUMP_DISPLAYLIST = 2;

    /** Profile a view. */
    private static final int VUOP_PROFILE_VIEW = 3;

    /** Invoke a method on the view. */
    private static final int VUOP_INVOKE_VIEW_METHOD = 4;

    /** Set layout parameter. */
    private static final int VUOP_SET_LAYOUT_PARAMETER = 5;

    /** Error code indicating operation specified in chunk is invalid. */
    private static final int ERR_INVALID_OP = -1;

    /** Error code indicating that the parameters are invalid. */
    private static final int ERR_INVALID_PARAM = -2;

    /** Error code indicating an exception while performing operation. */
    private static final int ERR_EXCEPTION = -3;

    private static final String TAG = "DdmViewDebug";

    private static final DdmHandleViewDebug sInstance = new DdmHandleViewDebug();

    /** singleton, do not instantiate. */
    private DdmHandleViewDebug() {}

    public static void register() {
        DdmServer.registerHandler(CHUNK_VULW, sInstance);
        DdmServer.registerHandler(CHUNK_VURT, sInstance);
        DdmServer.registerHandler(CHUNK_VUOP, sInstance);
    }

    @Override
    public void onConnected() {
    }

    @Override
    public void onDisconnected() {
    }

    @Override
    public Chunk handleChunk(Chunk request) {
        int type = request.type;

        if (type == CHUNK_VULW) {
            return listWindows();
        }

        ByteBuffer in = wrapChunk(request);
        int op = in.getInt();

        View rootView = getRootView(in);
        if (rootView == null) {
            return createFailChunk(ERR_INVALID_PARAM, "Invalid View Root");
        }

        if (type == CHUNK_VURT) {
            if (op == VURT_DUMP_HIERARCHY) {
                return dumpHierarchy(rootView, in);
            } else if (op == VURT_CAPTURE_LAYERS) {
                return captureLayers(rootView);
            } else if (op == VURT_DUMP_THEME) {
                return dumpTheme(rootView);
            } else {
                return createFailChunk(ERR_INVALID_OP, "Unknown view root operation: " + op);
            }
        }

        final View targetView = getTargetView(rootView, in);
        if (targetView == null) {
            return createFailChunk(ERR_INVALID_PARAM, "Invalid target view");
        }

        if (type == CHUNK_VUOP) {
            switch (op) {
                case VUOP_CAPTURE_VIEW:
                    return captureView(rootView, targetView);
                case VUOP_DUMP_DISPLAYLIST:
                    return dumpDisplayLists(rootView, targetView);
                case VUOP_PROFILE_VIEW:
                    return profileView(rootView, targetView);
                case VUOP_INVOKE_VIEW_METHOD:
                    return invokeViewMethod(rootView, targetView, in);
                case VUOP_SET_LAYOUT_PARAMETER:
                    return setLayoutParameter(rootView, targetView, in);
                default:
                    return createFailChunk(ERR_INVALID_OP, "Unknown view operation: " + op);
            }
        } else {
            throw new RuntimeException("Unknown packet " + name(type));
        }
    }

    /** Returns the list of windows owned by this client. */
    private Chunk listWindows() {
        String[] windowNames = WindowManagerGlobal.getInstance().getViewRootNames();

        int responseLength = 4;                     // # of windows
        for (String name : windowNames) {
            responseLength += 4;                    // length of next window name
            responseLength += name.length() * 2;    // window name
        }

        ByteBuffer out = ByteBuffer.allocate(responseLength);
        out.order(ChunkHandler.CHUNK_ORDER);

        out.putInt(windowNames.length);
        for (String name : windowNames) {
            out.putInt(name.length());
            putString(out, name);
        }

        return new Chunk(CHUNK_VULW, out);
    }

    private View getRootView(ByteBuffer in) {
        try {
            int viewRootNameLength = in.getInt();
            String viewRootName = getString(in, viewRootNameLength);
            return WindowManagerGlobal.getInstance().getRootView(viewRootName);
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    private View getTargetView(View root, ByteBuffer in) {
        int viewLength;
        String viewName;

        try {
            viewLength = in.getInt();
            viewName = getString(in, viewLength);
        } catch (BufferUnderflowException e) {
            return null;
        }

        return ViewDebug.findView(root, viewName);
    }

    /**
     * Returns the view hierarchy and/or view properties starting at the provided view.
     * Based on the input options, the return data may include:
     * - just the view hierarchy
     * - view hierarchy & the properties for each of the views
     * - just the view properties for a specific view.
     *  TODO: Currently this only returns views starting at the root, need to fix so that
     *  it can return properties of any view.
     */
    private Chunk dumpHierarchy(View rootView, ByteBuffer in) {
        boolean skipChildren = in.getInt() > 0;
        boolean includeProperties = in.getInt() > 0;
        boolean v2 = in.hasRemaining() && in.getInt() > 0;

        long start = System.currentTimeMillis();

        ByteArrayOutputStream b = new ByteArrayOutputStream(2 * 1024 * 1024);
        try {
            if (v2) {
                ViewDebug.dumpv2(rootView, b);
            } else {
                ViewDebug.dump(rootView, skipChildren, includeProperties, b);
            }
        } catch (IOException | InterruptedException e) {
            return createFailChunk(1, "Unexpected error while obtaining view hierarchy: "
                    + e.getMessage());
        }

        long end = System.currentTimeMillis();
        Log.d(TAG, "Time to obtain view hierarchy (ms): " + (end - start));

        byte[] data = b.toByteArray();
        return new Chunk(CHUNK_VURT, data, 0, data.length);
    }

    /** Returns a buffer with region details & bitmap of every single view. */
    private Chunk captureLayers(View rootView) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        DataOutputStream dos = new DataOutputStream(b);
        try {
            ViewDebug.captureLayers(rootView, dos);
        } catch (IOException e) {
            return createFailChunk(1, "Unexpected error while obtaining view hierarchy: "
                    + e.getMessage());
        } finally {
            try {
                dos.close();
            } catch (IOException e) {
                // ignore
            }
        }

        byte[] data = b.toByteArray();
        return new Chunk(CHUNK_VURT, data, 0, data.length);
    }

    /**
     * Returns the Theme dump of the provided view.
     */
    private Chunk dumpTheme(View rootView) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        try {
            ViewDebug.dumpTheme(rootView, b);
        } catch (IOException e) {
            return createFailChunk(1, "Unexpected error while dumping the theme: "
                    + e.getMessage());
        }

        byte[] data = b.toByteArray();
        return new Chunk(CHUNK_VURT, data, 0, data.length);
    }

    private Chunk captureView(View rootView, View targetView) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        try {
            ViewDebug.capture(rootView, b, targetView);
        } catch (IOException e) {
            return createFailChunk(1, "Unexpected error while capturing view: "
                    + e.getMessage());
        }

        byte[] data = b.toByteArray();
        return new Chunk(CHUNK_VUOP, data, 0, data.length);
    }

    /** Returns the display lists corresponding to the provided view. */
    private Chunk dumpDisplayLists(final View rootView, final View targetView) {
        rootView.post(new Runnable() {
            @Override
            public void run() {
                ViewDebug.outputDisplayList(rootView, targetView);
            }
        });
        return null;
    }

    /**
     * Invokes provided method on the view.
     * The method name and its arguments are passed in as inputs via the byte buffer.
     * The buffer contains:<ol>
     * <li> len(method name) </li>
     * <li> method name (encoded as UTF-16 2-byte characters) </li>
     * <li> # of args </li>
     * <li> arguments: Each argument comprises of a type specifier followed by the actual argument.
     * The type specifier is one character modelled after JNI signatures:
     *          <ul>
     *              <li>[ - array<br>
     *                This is followed by a second character according to this spec, indicating the
     *                array type, then the array length as an Int, followed by a repeated encoding
     *                of the actual data.
     *                WARNING: Only <b>byte[]</b> is supported currently.
     *              </li>
     *              <li>Z - boolean<br>
     *                 Booleans are encoded via bytes with 0 indicating false</li>
     *              <li>B - byte</li>
     *              <li>C - char</li>
     *              <li>S - short</li>
     *              <li>I - int</li>
     *              <li>J - long</li>
     *              <li>F - float</li>
     *              <li>D - double</li>
     *              <li>V - void<br>
     *                NOT followed by a value. Only used for return types</li>
     *              <li>R - String (not a real JNI type, but added for convenience)<br>
     *                Strings are encoded as an unsigned short of the number of <b>bytes</b>,
     *                followed by the actual UTF-8 encoded bytes.
     *                WARNING: This is the same encoding as produced by
     *                ViewHierarchyEncoder#writeString. However, note that this encoding is
     *                different to what DdmHandle#getString() expects, which is used in other places
     *                in this class.
     *                WARNING: Since the length is the number of UTF-8 encoded bytes, Strings can
     *                contain up to 64k ASCII characters, yet depending on the actual data, the true
     *                maximum might be as little as 21844 unicode characters.
     *                <b>null</b> String objects are encoded as an empty string
     *              </li>
     *            </ul>
     *   </li>
     * </ol>
     * Methods that take no arguments need only specify the method name.
     *
     * The return value is encoded the same way as a single parameter (type + value)
     */
    private Chunk invokeViewMethod(View rootView, final View targetView, ByteBuffer in) {
        int l = in.getInt();
        String methodName = getString(in, l);

        try {
            byte[] returnValue =  ViewDebug.invokeViewMethod(targetView, methodName, in);
            return new Chunk(CHUNK_VUOP, returnValue, 0, returnValue.length);
        } catch (ViewDebug.ViewMethodInvocationSerializationException e) {
            return createFailChunk(ERR_INVALID_PARAM, e.getMessage());
        } catch (Exception e) {
            return createFailChunk(ERR_EXCEPTION, e.getMessage());
        }
    }

    private Chunk setLayoutParameter(final View rootView, final View targetView, ByteBuffer in) {
        int l = in.getInt();
        String param = getString(in, l);
        int value = in.getInt();
        try {
            ViewDebug.setLayoutParameter(targetView, param, value);
        } catch (Exception e) {
            Log.e(TAG, "Exception setting layout parameter: " + e);
            return createFailChunk(ERR_EXCEPTION, "Error accessing field "
                    + param + ":" + e.getMessage());
        }

        return null;
    }

    /** Profiles provided view. */
    private Chunk profileView(View rootView, final View targetView) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(32 * 1024);
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(b), 32 * 1024);
        try {
            ViewDebug.profileViewAndChildren(targetView, bw);
        } catch (IOException e) {
            return createFailChunk(1, "Unexpected error while profiling view: " + e.getMessage());
        } finally {
            try {
                bw.close();
            } catch (IOException e) {
                // ignore
            }
        }

        byte[] data = b.toByteArray();
        return new Chunk(CHUNK_VUOP, data, 0, data.length);
    }
}

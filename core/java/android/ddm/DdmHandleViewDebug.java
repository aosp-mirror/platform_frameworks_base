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

import android.opengl.GLUtils;
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
public class DdmHandleViewDebug extends ChunkHandler {
    /** Enable/Disable tracing of OpenGL calls. */
    public static final int CHUNK_VUGL = type("VUGL");

    /** List {@link ViewRootImpl}'s of this process. */
    private static final int CHUNK_VULW = type("VULW");

    /** Operation on view root, first parameter in packet should be one of VURT_* constants */
    private static final int CHUNK_VURT = type("VURT");

    /** Dump view hierarchy. */
    private static final int VURT_DUMP_HIERARCHY = 1;

    /** Capture View Layers. */
    private static final int VURT_CAPTURE_LAYERS = 2;

    /**
     * Generic View Operation, first parameter in the packet should be one of the
     * VUOP_* constants below.
     */
    private static final int CHUNK_VUOP = type("VUOP");

    /** Capture View. */
    private static final int VUOP_CAPTURE_VIEW = 1;

    /** Obtain the Display List corresponding to the view. */
    private static final int VUOP_DUMP_DISPLAYLIST = 2;

    /** Invalidate View. */
    private static final int VUOP_INVALIDATE_VIEW = 3;

    /** Re-layout given view. */
    private static final int VUOP_LAYOUT_VIEW = 4;

    /** Profile a view. */
    private static final int VUOP_PROFILE_VIEW = 5;

    /** Error code indicating operation specified in chunk is invalid. */
    private static final int ERR_INVALID_OP = -1;

    /** Error code indicating that the parameters are invalid. */
    private static final int ERR_INVALID_PARAM = -2;

    private static final DdmHandleViewDebug sInstance = new DdmHandleViewDebug();

    /** singleton, do not instantiate. */
    private DdmHandleViewDebug() {}

    public static void register() {
        DdmServer.registerHandler(CHUNK_VUGL, sInstance);
        DdmServer.registerHandler(CHUNK_VULW, sInstance);
        DdmServer.registerHandler(CHUNK_VURT, sInstance);
        DdmServer.registerHandler(CHUNK_VUOP, sInstance);
    }

    @Override
    public void connected() {
    }

    @Override
    public void disconnected() {
    }

    @Override
    public Chunk handleChunk(Chunk request) {
        int type = request.type;

        if (type == CHUNK_VUGL) {
            return handleOpenGlTrace(request);
        } else if (type == CHUNK_VULW) {
            return listWindows();
        }

        ByteBuffer in = wrapChunk(request);
        int op = in.getInt();

        View rootView = getRootView(in);
        if (rootView == null) {
            return createFailChunk(ERR_INVALID_PARAM, "Invalid View Root");
        }

        if (type == CHUNK_VURT) {
            if (op == VURT_DUMP_HIERARCHY)
                return dumpHierarchy(rootView, in);
            else if (op == VURT_CAPTURE_LAYERS)
                return captureLayers(rootView);
            else
                return createFailChunk(ERR_INVALID_OP, "Unknown view root operation: " + op);
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
                case VUOP_INVALIDATE_VIEW:
                    return invalidateView(rootView, targetView);
                case VUOP_LAYOUT_VIEW:
                    return layoutView(rootView, targetView);
                case VUOP_PROFILE_VIEW:
                    return profileView(rootView, targetView);
                default:
                    return createFailChunk(ERR_INVALID_OP, "Unknown view operation: " + op);
            }
        } else {
            throw new RuntimeException("Unknown packet " + ChunkHandler.name(type));
        }
    }

    private Chunk handleOpenGlTrace(Chunk request) {
        ByteBuffer in = wrapChunk(request);
        GLUtils.setTracingLevel(in.getInt());
        return null;    // empty response
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
     *  - just the view hierarchy
     *  - view hierarchy & the properties for each of the views
     *  - just the view properties for a specific view.
     *  TODO: Currently this only returns views starting at the root, need to fix so that
     *  it can return properties of any view.
     */
    private Chunk dumpHierarchy(View rootView, ByteBuffer in) {
        boolean skipChildren = in.getInt() > 0;
        boolean includeProperties = in.getInt() > 0;

        ByteArrayOutputStream b = new ByteArrayOutputStream(1024);
        try {
            ViewDebug.dump(rootView, skipChildren, includeProperties, b);
        } catch (IOException e) {
            return createFailChunk(1, "Unexpected error while obtaining view hierarchy: "
                    + e.getMessage());
        }

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

    /** Invalidates provided view. */
    private Chunk invalidateView(final View rootView, final View targetView) {
        targetView.postInvalidate();
        return null;
    }

    /** Lays out provided view. */
    private Chunk layoutView(View rootView, final View targetView) {
        rootView.post(new Runnable() {
            @Override
            public void run() {
                targetView.requestLayout();
            }
        });
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

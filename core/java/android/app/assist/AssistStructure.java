package android.app.assist;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PooledStringReader;
import android.os.PooledStringWriter;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import java.util.ArrayList;

/**
 * Assist data automatically created by the platform's implementation
 * of {@link android.app.Activity#onProvideAssistData}.
 */
public class AssistStructure implements Parcelable {
    static final String TAG = "AssistStructure";

    static final boolean DEBUG_PARCEL = false;
    static final boolean DEBUG_PARCEL_CHILDREN = false;
    static final boolean DEBUG_PARCEL_TREE = false;

    static final int VALIDATE_WINDOW_TOKEN = 0x11111111;
    static final int VALIDATE_VIEW_TOKEN = 0x22222222;

    boolean mHaveData;

    ComponentName mActivityComponent;

    final ArrayList<WindowNode> mWindowNodes = new ArrayList<>();

    final ArrayList<ViewNodeBuilder> mPendingAsyncChildren = new ArrayList<>();

    SendChannel mSendChannel;
    IBinder mReceiveChannel;

    Rect mTmpRect = new Rect();

    static final int TRANSACTION_XFER = Binder.FIRST_CALL_TRANSACTION+1;
    static final String DESCRIPTOR = "android.app.AssistStructure";

    final static class SendChannel extends Binder {
        volatile AssistStructure mAssistStructure;

        SendChannel(AssistStructure as) {
            mAssistStructure = as;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TRANSACTION_XFER) {
                AssistStructure as = mAssistStructure;
                if (as == null) {
                    return true;
                }

                data.enforceInterface(DESCRIPTOR);
                IBinder token = data.readStrongBinder();
                if (DEBUG_PARCEL) Log.d(TAG, "Request for data on " + as
                        + " using token " + token);
                if (token != null) {
                    if (DEBUG_PARCEL) Log.d(TAG, "Resuming partial write of " + token);
                    if (token instanceof ParcelTransferWriter) {
                        ParcelTransferWriter xfer = (ParcelTransferWriter)token;
                        xfer.writeToParcel(as, reply);
                        return true;
                    }
                    Log.w(TAG, "Caller supplied bad token type: " + token);
                    // Don't write anything; this is the end of the data.
                    return true;
                }
                //long start = SystemClock.uptimeMillis();
                ParcelTransferWriter xfer = new ParcelTransferWriter(as, reply);
                xfer.writeToParcel(as, reply);
                //Log.i(TAG, "Time to parcel: " + (SystemClock.uptimeMillis()-start) + "ms");
                return true;
            } else {
                return super.onTransact(code, data, reply, flags);
            }
        }
    }

    final static class ViewStackEntry {
        ViewNode node;
        int curChild;
        int numChildren;
    }

    final static class ParcelTransferWriter extends Binder {
        final boolean mWriteStructure;
        int mCurWindow;
        int mNumWindows;
        final ArrayList<ViewStackEntry> mViewStack = new ArrayList<>();
        ViewStackEntry mCurViewStackEntry;
        int mCurViewStackPos;
        int mNumWrittenWindows;
        int mNumWrittenViews;
        final float[] mTmpMatrix = new float[9];

        ParcelTransferWriter(AssistStructure as, Parcel out) {
            mWriteStructure = as.waitForReady();
            ComponentName.writeToParcel(as.mActivityComponent, out);
            mNumWindows = as.mWindowNodes.size();
            if (mWriteStructure && mNumWindows > 0) {
                out.writeInt(mNumWindows);
            } else {
                out.writeInt(0);
            }
        }

        void writeToParcel(AssistStructure as, Parcel out) {
            int start = out.dataPosition();
            mNumWrittenWindows = 0;
            mNumWrittenViews = 0;
            boolean more = writeToParcelInner(as, out);
            Log.i(TAG, "Flattened " + (more ? "partial" : "final") + " assist data: "
                    + (out.dataPosition() - start)
                    + " bytes, containing " + mNumWrittenWindows + " windows, "
                    + mNumWrittenViews + " views");
        }

        boolean writeToParcelInner(AssistStructure as, Parcel out) {
            if (mNumWindows == 0) {
                return false;
            }
            if (DEBUG_PARCEL) Log.d(TAG, "Creating PooledStringWriter @ " + out.dataPosition());
            PooledStringWriter pwriter = new PooledStringWriter(out);
            while (writeNextEntryToParcel(as, out, pwriter)) {
                // If the parcel is above the IPC limit, then we are getting too
                // large for a single IPC so stop here and let the caller come back when it
                // is ready for more.
                if (out.dataSize() > IBinder.MAX_IPC_SIZE) {
                    if (DEBUG_PARCEL) Log.d(TAG, "Assist data size is " + out.dataSize()
                            + " @ pos " + out.dataPosition() + "; returning partial result");
                    out.writeInt(0);
                    out.writeStrongBinder(this);
                    if (DEBUG_PARCEL) Log.d(TAG, "Finishing PooledStringWriter @ "
                            + out.dataPosition() + ", size " + pwriter.getStringCount());
                    pwriter.finish();
                    return true;
                }
            }
            if (DEBUG_PARCEL) Log.d(TAG, "Finishing PooledStringWriter @ "
                    + out.dataPosition() + ", size " + pwriter.getStringCount());
            pwriter.finish();
            mViewStack.clear();
            return false;
        }

        void pushViewStackEntry(ViewNode node, int pos) {
            ViewStackEntry entry;
            if (pos >= mViewStack.size()) {
                entry = new ViewStackEntry();
                mViewStack.add(entry);
                if (DEBUG_PARCEL_TREE) Log.d(TAG, "New stack entry at " + pos + ": " + entry);
            } else {
                entry = mViewStack.get(pos);
                if (DEBUG_PARCEL_TREE) Log.d(TAG, "Existing stack entry at " + pos + ": " + entry);
            }
            entry.node = node;
            entry.numChildren = node.getChildCount();
            entry.curChild = 0;
            mCurViewStackEntry = entry;
        }

        void writeView(ViewNode child, Parcel out, PooledStringWriter pwriter, int levelAdj) {
            if (DEBUG_PARCEL) Log.d(TAG, "write view: at " + out.dataPosition()
                    + ", windows=" + mNumWrittenWindows
                    + ", views=" + mNumWrittenViews
                    + ", level=" + (mCurViewStackPos+levelAdj));
            out.writeInt(VALIDATE_VIEW_TOKEN);
            int flags = child.writeSelfToParcel(out, pwriter, mTmpMatrix);
            mNumWrittenViews++;
            // If the child has children, push it on the stack to write them next.
            if ((flags&ViewNode.FLAGS_HAS_CHILDREN) != 0) {
                if (DEBUG_PARCEL_TREE || DEBUG_PARCEL_CHILDREN) Log.d(TAG,
                        "Preparing to write " + child.mChildren.length
                                + " children: @ #" + mNumWrittenViews
                                + ", level " + (mCurViewStackPos+levelAdj));
                out.writeInt(child.mChildren.length);
                int pos = ++mCurViewStackPos;
                pushViewStackEntry(child, pos);
            }
        }

        boolean writeNextEntryToParcel(AssistStructure as, Parcel out, PooledStringWriter pwriter) {
            // Write next view node if appropriate.
            if (mCurViewStackEntry != null) {
                if (mCurViewStackEntry.curChild < mCurViewStackEntry.numChildren) {
                    // Write the next child in the current view.
                    if (DEBUG_PARCEL_TREE) Log.d(TAG, "Writing child #"
                            + mCurViewStackEntry.curChild + " in " + mCurViewStackEntry.node);
                    ViewNode child = mCurViewStackEntry.node.mChildren[mCurViewStackEntry.curChild];
                    mCurViewStackEntry.curChild++;
                    writeView(child, out, pwriter, 1);
                    return true;
                }

                // We are done writing children of the current view; pop off the stack.
                do {
                    int pos = --mCurViewStackPos;
                    if (DEBUG_PARCEL_TREE) Log.d(TAG, "Done with " + mCurViewStackEntry.node
                            + "; popping up to " + pos);
                    if (pos < 0) {
                        // Reached the last view; step to next window.
                        if (DEBUG_PARCEL_TREE) Log.d(TAG, "Done with view hierarchy!");
                        mCurViewStackEntry = null;
                        break;
                    }
                    mCurViewStackEntry = mViewStack.get(pos);
                } while (mCurViewStackEntry.curChild >= mCurViewStackEntry.numChildren);
                return true;
            }

            // Write the next window if appropriate.
            int pos = mCurWindow;
            if (pos < mNumWindows) {
                WindowNode win = as.mWindowNodes.get(pos);
                mCurWindow++;
                if (DEBUG_PARCEL) Log.d(TAG, "write window #" + pos + ": at " + out.dataPosition()
                        + ", windows=" + mNumWrittenWindows
                        + ", views=" + mNumWrittenViews);
                out.writeInt(VALIDATE_WINDOW_TOKEN);
                win.writeSelfToParcel(out, pwriter, mTmpMatrix);
                mNumWrittenWindows++;
                ViewNode root = win.mRoot;
                mCurViewStackPos = 0;
                if (DEBUG_PARCEL_TREE) Log.d(TAG, "Writing initial root view " + root);
                writeView(root, out, pwriter, 0);
                return true;
            }

            return false;
        }
    }

    final class ParcelTransferReader {
        final float[] mTmpMatrix = new float[9];
        PooledStringReader mStringReader;

        int mNumReadWindows;
        int mNumReadViews;

        private final IBinder mChannel;
        private IBinder mTransferToken;
        private Parcel mCurParcel;

        ParcelTransferReader(IBinder channel) {
            mChannel = channel;
        }

        void go() {
            fetchData();
            mActivityComponent = ComponentName.readFromParcel(mCurParcel);
            final int N = mCurParcel.readInt();
            if (N > 0) {
                if (DEBUG_PARCEL) Log.d(TAG, "Creating PooledStringReader @ "
                        + mCurParcel.dataPosition());
                mStringReader = new PooledStringReader(mCurParcel);
                if (DEBUG_PARCEL) Log.d(TAG, "PooledStringReader size = "
                        + mStringReader.getStringCount());
                for (int i=0; i<N; i++) {
                    mWindowNodes.add(new WindowNode(this));
                }
            }
            if (DEBUG_PARCEL) Log.d(TAG, "Finished reading: at " + mCurParcel.dataPosition()
                    + ", avail=" + mCurParcel.dataAvail() + ", windows=" + mNumReadWindows
                    + ", views=" + mNumReadViews);
        }

        Parcel readParcel(int validateToken, int level) {
            if (DEBUG_PARCEL) Log.d(TAG, "readParcel: at " + mCurParcel.dataPosition()
                    + ", avail=" + mCurParcel.dataAvail() + ", windows=" + mNumReadWindows
                    + ", views=" + mNumReadViews + ", level=" + level);
            int token = mCurParcel.readInt();
            if (token != 0) {
                if (token != validateToken) {
                    throw new BadParcelableException("Got token " + Integer.toHexString(token)
                            + ", expected token " + Integer.toHexString(validateToken));
                }
                return mCurParcel;
            }
            // We have run out of partial data, need to read another batch.
            mTransferToken = mCurParcel.readStrongBinder();
            if (mTransferToken == null) {
                throw new IllegalStateException(
                        "Reached end of partial data without transfer token");
            }
            if (DEBUG_PARCEL) Log.d(TAG, "Ran out of partial data at "
                    + mCurParcel.dataPosition() + ", token " + mTransferToken);
            fetchData();
            if (DEBUG_PARCEL) Log.d(TAG, "Creating PooledStringReader @ "
                    + mCurParcel.dataPosition());
            mStringReader = new PooledStringReader(mCurParcel);
            if (DEBUG_PARCEL) Log.d(TAG, "PooledStringReader size = "
                    + mStringReader.getStringCount());
            if (DEBUG_PARCEL) Log.d(TAG, "readParcel: at " + mCurParcel.dataPosition()
                    + ", avail=" + mCurParcel.dataAvail() + ", windows=" + mNumReadWindows
                    + ", views=" + mNumReadViews);
            mCurParcel.readInt();
            return mCurParcel;
        }

        private void fetchData() {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(mTransferToken);
            if (DEBUG_PARCEL) Log.d(TAG, "Requesting data with token " + mTransferToken);
            if (mCurParcel != null) {
                mCurParcel.recycle();
            }
            mCurParcel = Parcel.obtain();
            try {
                mChannel.transact(TRANSACTION_XFER, data, mCurParcel, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failure reading AssistStructure data", e);
                throw new IllegalStateException("Failure reading AssistStructure data: " + e);
            }
            data.recycle();
            mNumReadWindows = mNumReadViews = 0;
        }
    }

    final static class ViewNodeText {
        CharSequence mText;
        float mTextSize;
        int mTextStyle;
        int mTextColor = ViewNode.TEXT_COLOR_UNDEFINED;
        int mTextBackgroundColor = ViewNode.TEXT_COLOR_UNDEFINED;
        int mTextSelectionStart;
        int mTextSelectionEnd;
        int[] mLineCharOffsets;
        int[] mLineBaselines;
        String mHint;

        ViewNodeText() {
        }

        boolean isSimple() {
            return mTextBackgroundColor == ViewNode.TEXT_COLOR_UNDEFINED
                    && mTextSelectionStart == 0 && mTextSelectionEnd == 0
                    && mLineCharOffsets == null && mLineBaselines == null && mHint == null;
        }

        ViewNodeText(Parcel in, boolean simple) {
            mText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            mTextSize = in.readFloat();
            mTextStyle = in.readInt();
            mTextColor = in.readInt();
            if (!simple) {
                mTextBackgroundColor = in.readInt();
                mTextSelectionStart = in.readInt();
                mTextSelectionEnd = in.readInt();
                mLineCharOffsets = in.createIntArray();
                mLineBaselines = in.createIntArray();
                mHint = in.readString();
            }
        }

        void writeToParcel(Parcel out, boolean simple) {
            TextUtils.writeToParcel(mText, out, 0);
            out.writeFloat(mTextSize);
            out.writeInt(mTextStyle);
            out.writeInt(mTextColor);
            if (!simple) {
                out.writeInt(mTextBackgroundColor);
                out.writeInt(mTextSelectionStart);
                out.writeInt(mTextSelectionEnd);
                out.writeIntArray(mLineCharOffsets);
                out.writeIntArray(mLineBaselines);
                out.writeString(mHint);
            }
        }
    }

    /**
     * Describes a window in the assist data.
     */
    static public class WindowNode {
        final int mX;
        final int mY;
        final int mWidth;
        final int mHeight;
        final CharSequence mTitle;
        final int mDisplayId;
        final ViewNode mRoot;

        WindowNode(AssistStructure assist, ViewRootImpl root) {
            View view = root.getView();
            Rect rect = new Rect();
            view.getBoundsOnScreen(rect);
            mX = rect.left - view.getLeft();
            mY = rect.top - view.getTop();
            mWidth = rect.width();
            mHeight = rect.height();
            mTitle = root.getTitle();
            mDisplayId = root.getDisplayId();
            mRoot = new ViewNode();
            ViewNodeBuilder builder = new ViewNodeBuilder(assist, mRoot, false);
            if ((root.getWindowFlags()& WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                // This is a secure window, so it doesn't want a screenshot, and that
                // means we should also not copy out its view hierarchy.
                view.onProvideStructure(builder);
                builder.setAssistBlocked(true);
                return;
            }
            view.dispatchProvideStructure(builder);
        }

        WindowNode(ParcelTransferReader reader) {
            Parcel in = reader.readParcel(VALIDATE_WINDOW_TOKEN, 0);
            reader.mNumReadWindows++;
            mX = in.readInt();
            mY = in.readInt();
            mWidth = in.readInt();
            mHeight = in.readInt();
            mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            mDisplayId = in.readInt();
            mRoot = new ViewNode(reader, 0);
        }

        void writeSelfToParcel(Parcel out, PooledStringWriter pwriter, float[] tmpMatrix) {
            out.writeInt(mX);
            out.writeInt(mY);
            out.writeInt(mWidth);
            out.writeInt(mHeight);
            TextUtils.writeToParcel(mTitle, out, 0);
            out.writeInt(mDisplayId);
        }

        /**
         * Returns the left edge of the window, in pixels, relative to the left
         * edge of the screen.
         */
        public int getLeft() {
            return mX;
        }

        /**
         * Returns the top edge of the window, in pixels, relative to the top
         * edge of the screen.
         */
        public int getTop() {
            return mY;
        }

        /**
         * Returns the total width of the window in pixels.
         */
        public int getWidth() {
            return mWidth;
        }

        /**
         * Returns the total height of the window in pixels.
         */
        public int getHeight() {
            return mHeight;
        }

        /**
         * Returns the title associated with the window, if it has one.
         */
        public CharSequence getTitle() {
            return mTitle;
        }

        /**
         * Returns the ID of the display this window is on, for use with
         * {@link android.hardware.display.DisplayManager#getDisplay DisplayManager.getDisplay()}.
         */
        public int getDisplayId() {
            return mDisplayId;
        }

        /**
         * Returns the {@link ViewNode} containing the root content of the window.
         */
        public ViewNode getRootViewNode() {
            return mRoot;
        }
    }

    /**
     * Describes a single view in the assist data.
     */
    static public class ViewNode {
        /**
         * Magic value for text color that has not been defined, which is very unlikely
         * to be confused with a real text color.
         */
        public static final int TEXT_COLOR_UNDEFINED = 1;

        public static final int TEXT_STYLE_BOLD = 1<<0;
        public static final int TEXT_STYLE_ITALIC = 1<<1;
        public static final int TEXT_STYLE_UNDERLINE = 1<<2;
        public static final int TEXT_STYLE_STRIKE_THRU = 1<<3;

        int mId = View.NO_ID;
        String mIdPackage;
        String mIdType;
        String mIdEntry;
        int mX;
        int mY;
        int mScrollX;
        int mScrollY;
        int mWidth;
        int mHeight;
        Matrix mMatrix;
        float mElevation;
        float mAlpha = 1.0f;

        static final int FLAGS_DISABLED = 0x00000001;
        static final int FLAGS_VISIBILITY_MASK = View.VISIBLE|View.INVISIBLE|View.GONE;
        static final int FLAGS_FOCUSABLE = 0x00000010;
        static final int FLAGS_FOCUSED = 0x00000020;
        static final int FLAGS_SELECTED = 0x00000040;
        static final int FLAGS_ASSIST_BLOCKED = 0x00000080;
        static final int FLAGS_CHECKABLE = 0x00000100;
        static final int FLAGS_CHECKED = 0x00000200;
        static final int FLAGS_CLICKABLE = 0x00000400;
        static final int FLAGS_LONG_CLICKABLE = 0x00000800;
        static final int FLAGS_ACCESSIBILITY_FOCUSED = 0x00001000;
        static final int FLAGS_ACTIVATED = 0x00002000;
        static final int FLAGS_CONTEXT_CLICKABLE = 0x00004000;

        static final int FLAGS_HAS_MATRIX = 0x40000000;
        static final int FLAGS_HAS_ALPHA = 0x20000000;
        static final int FLAGS_HAS_ELEVATION = 0x10000000;
        static final int FLAGS_HAS_SCROLL = 0x08000000;
        static final int FLAGS_HAS_LARGE_COORDS = 0x04000000;
        static final int FLAGS_HAS_CONTENT_DESCRIPTION = 0x02000000;
        static final int FLAGS_HAS_TEXT = 0x01000000;
        static final int FLAGS_HAS_COMPLEX_TEXT = 0x00800000;
        static final int FLAGS_HAS_EXTRAS = 0x00400000;
        static final int FLAGS_HAS_ID = 0x00200000;
        static final int FLAGS_HAS_CHILDREN = 0x00100000;
        static final int FLAGS_ALL_CONTROL = 0xfff00000;

        int mFlags;

        String mClassName;
        CharSequence mContentDescription;

        ViewNodeText mText;
        Bundle mExtras;

        ViewNode[] mChildren;

        ViewNode() {
        }

        ViewNode(ParcelTransferReader reader, int nestingLevel) {
            final Parcel in = reader.readParcel(VALIDATE_VIEW_TOKEN, nestingLevel);
            reader.mNumReadViews++;
            final PooledStringReader preader = reader.mStringReader;
            mClassName = preader.readString();
            mFlags = in.readInt();
            final int flags = mFlags;
            if ((flags&FLAGS_HAS_ID) != 0) {
                mId = in.readInt();
                if (mId != 0) {
                    mIdEntry = preader.readString();
                    if (mIdEntry != null) {
                        mIdType = preader.readString();
                        mIdPackage = preader.readString();
                    }
                }
            }
            if ((flags&FLAGS_HAS_LARGE_COORDS) != 0) {
                mX = in.readInt();
                mY = in.readInt();
                mWidth = in.readInt();
                mHeight = in.readInt();
            } else {
                int val = in.readInt();
                mX = val&0x7fff;
                mY = (val>>16)&0x7fff;
                val = in.readInt();
                mWidth = val&0x7fff;
                mHeight = (val>>16)&0x7fff;
            }
            if ((flags&FLAGS_HAS_SCROLL) != 0) {
                mScrollX = in.readInt();
                mScrollY = in.readInt();
            }
            if ((flags&FLAGS_HAS_MATRIX) != 0) {
                mMatrix = new Matrix();
                in.readFloatArray(reader.mTmpMatrix);
                mMatrix.setValues(reader.mTmpMatrix);
            }
            if ((flags&FLAGS_HAS_ELEVATION) != 0) {
                mElevation = in.readFloat();
            }
            if ((flags&FLAGS_HAS_ALPHA) != 0) {
                mAlpha = in.readFloat();
            }
            if ((flags&FLAGS_HAS_CONTENT_DESCRIPTION) != 0) {
                mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            }
            if ((flags&FLAGS_HAS_TEXT) != 0) {
                mText = new ViewNodeText(in, (flags&FLAGS_HAS_COMPLEX_TEXT) == 0);
            }
            if ((flags&FLAGS_HAS_EXTRAS) != 0) {
                mExtras = in.readBundle();
            }
            if ((flags&FLAGS_HAS_CHILDREN) != 0) {
                final int NCHILDREN = in.readInt();
                if (DEBUG_PARCEL_TREE || DEBUG_PARCEL_CHILDREN) Log.d(TAG,
                        "Preparing to read " + NCHILDREN
                                + " children: @ #" + reader.mNumReadViews
                                + ", level " + nestingLevel);
                mChildren = new ViewNode[NCHILDREN];
                for (int i=0; i<NCHILDREN; i++) {
                    mChildren[i] = new ViewNode(reader, nestingLevel + 1);
                }
            }
        }

        int writeSelfToParcel(Parcel out, PooledStringWriter pwriter, float[] tmpMatrix) {
            int flags = mFlags & ~FLAGS_ALL_CONTROL;
            if (mId != View.NO_ID) {
                flags |= FLAGS_HAS_ID;
            }
            if ((mX&~0x7fff) != 0 || (mY&~0x7fff) != 0
                    || (mWidth&~0x7fff) != 0 | (mHeight&~0x7fff) != 0) {
                flags |= FLAGS_HAS_LARGE_COORDS;
            }
            if (mScrollX != 0 || mScrollY != 0) {
                flags |= FLAGS_HAS_SCROLL;
            }
            if (mMatrix != null) {
                flags |= FLAGS_HAS_MATRIX;
            }
            if (mElevation != 0) {
                flags |= FLAGS_HAS_ELEVATION;
            }
            if (mAlpha != 1.0f) {
                flags |= FLAGS_HAS_ALPHA;
            }
            if (mContentDescription != null) {
                flags |= FLAGS_HAS_CONTENT_DESCRIPTION;
            }
            if (mText != null) {
                flags |= FLAGS_HAS_TEXT;
                if (!mText.isSimple()) {
                    flags |= FLAGS_HAS_COMPLEX_TEXT;
                }
            }
            if (mExtras != null) {
                flags |= FLAGS_HAS_EXTRAS;
            }
            if (mChildren != null) {
                flags |= FLAGS_HAS_CHILDREN;
            }

            pwriter.writeString(mClassName);
            out.writeInt(flags);
            if ((flags&FLAGS_HAS_ID) != 0) {
                out.writeInt(mId);
                if (mId != 0) {
                    pwriter.writeString(mIdEntry);
                    if (mIdEntry != null) {
                        pwriter.writeString(mIdType);
                        pwriter.writeString(mIdPackage);
                    }
                }
            }
            if ((flags&FLAGS_HAS_LARGE_COORDS) != 0) {
                out.writeInt(mX);
                out.writeInt(mY);
                out.writeInt(mWidth);
                out.writeInt(mHeight);
            } else {
                out.writeInt((mY<<16) | mX);
                out.writeInt((mHeight<<16) | mWidth);
            }
            if ((flags&FLAGS_HAS_SCROLL) != 0) {
                out.writeInt(mScrollX);
                out.writeInt(mScrollY);
            }
            if ((flags&FLAGS_HAS_MATRIX) != 0) {
                mMatrix.getValues(tmpMatrix);
                out.writeFloatArray(tmpMatrix);
            }
            if ((flags&FLAGS_HAS_ELEVATION) != 0) {
                out.writeFloat(mElevation);
            }
            if ((flags&FLAGS_HAS_ALPHA) != 0) {
                out.writeFloat(mAlpha);
            }
            if ((flags&FLAGS_HAS_CONTENT_DESCRIPTION) != 0) {
                TextUtils.writeToParcel(mContentDescription, out, 0);
            }
            if ((flags&FLAGS_HAS_TEXT) != 0) {
                mText.writeToParcel(out, (flags&FLAGS_HAS_COMPLEX_TEXT) == 0);
            }
            if ((flags&FLAGS_HAS_EXTRAS) != 0) {
                out.writeBundle(mExtras);
            }
            return flags;
        }

        /**
         * Returns the ID associated with this view, as per {@link View#getId() View.getId()}.
         */
        public int getId() {
            return mId;
        }

        /**
         * If {@link #getId()} is a resource identifier, this is the package name of that
         * identifier.  See {@link android.view.ViewStructure#setId ViewStructure.setId}
         * for more information.
         */
        public String getIdPackage() {
            return mIdPackage;
        }

        /**
         * If {@link #getId()} is a resource identifier, this is the type name of that
         * identifier.  See {@link android.view.ViewStructure#setId ViewStructure.setId}
         * for more information.
         */
        public String getIdType() {
            return mIdType;
        }

        /**
         * If {@link #getId()} is a resource identifier, this is the entry name of that
         * identifier.  See {@link android.view.ViewStructure#setId ViewStructure.setId}
         * for more information.
         */
        public String getIdEntry() {
            return mIdEntry;
        }

        /**
         * Returns the left edge of this view, in pixels, relative to the left edge of its parent.
         */
        public int getLeft() {
            return mX;
        }

        /**
         * Returns the top edge of this view, in pixels, relative to the top edge of its parent.
         */
        public int getTop() {
            return mY;
        }

        /**
         * Returns the current X scroll offset of this view, as per
         * {@link android.view.View#getScrollX() View.getScrollX()}.
         */
        public int getScrollX() {
            return mScrollX;
        }

        /**
         * Returns the current Y scroll offset of this view, as per
         * {@link android.view.View#getScrollX() View.getScrollY()}.
         */
        public int getScrollY() {
            return mScrollY;
        }

        /**
         * Returns the width of this view, in pixels.
         */
        public int getWidth() {
            return mWidth;
        }

        /**
         * Returns the height of this view, in pixels.
         */
        public int getHeight() {
            return mHeight;
        }

        /**
         * Returns the transformation that has been applied to this view, such as a translation
         * or scaling.  The returned Matrix object is owned by ViewNode; do not modify it.
         * Returns null if there is no transformation applied to the view.
         */
        public Matrix getTransformation() {
            return mMatrix;
        }

        /**
         * Returns the visual elevation of the view, used for shadowing and other visual
         * characterstics, as set by {@link ViewStructure#setElevation
         * ViewStructure.setElevation(float)}.
         */
        public float getElevation() {
            return mElevation;
        }

        /**
         * Returns the alpha transformation of the view, used to reduce the overall opacity
         * of the view's contents, as set by {@link ViewStructure#setAlpha
         * ViewStructure.setAlpha(float)}.
         */
        public float getAlpha() {
            return mAlpha;
        }

        /**
         * Returns the visibility mode of this view, as per
         * {@link android.view.View#getVisibility() View.getVisibility()}.
         */
        public int getVisibility() {
            return mFlags&ViewNode.FLAGS_VISIBILITY_MASK;
        }

        /**
         * Returns true if assist data has been blocked starting at this node in the hierarchy.
         */
        public boolean isAssistBlocked() {
            return (mFlags&ViewNode.FLAGS_ASSIST_BLOCKED) != 0;
        }

        /**
         * Returns true if this node is in an enabled state.
         */
        public boolean isEnabled() {
            return (mFlags&ViewNode.FLAGS_DISABLED) == 0;
        }

        /**
         * Returns true if this node is clickable by the user.
         */
        public boolean isClickable() {
            return (mFlags&ViewNode.FLAGS_CLICKABLE) != 0;
        }

        /**
         * Returns true if this node can take input focus.
         */
        public boolean isFocusable() {
            return (mFlags&ViewNode.FLAGS_FOCUSABLE) != 0;
        }

        /**
         * Returns true if this node currently had input focus at the time that the
         * structure was collected.
         */
        public boolean isFocused() {
            return (mFlags&ViewNode.FLAGS_FOCUSED) != 0;
        }

        /**
         * Returns true if this node currently had accessibility focus at the time that the
         * structure was collected.
         */
        public boolean isAccessibilityFocused() {
            return (mFlags&ViewNode.FLAGS_ACCESSIBILITY_FOCUSED) != 0;
        }

        /**
         * Returns true if this node represents something that is checkable by the user.
         */
        public boolean isCheckable() {
            return (mFlags&ViewNode.FLAGS_CHECKABLE) != 0;
        }

        /**
         * Returns true if this node is currently in a checked state.
         */
        public boolean isChecked() {
            return (mFlags&ViewNode.FLAGS_CHECKED) != 0;
        }

        /**
         * Returns true if this node has currently been selected by the user.
         */
        public boolean isSelected() {
            return (mFlags&ViewNode.FLAGS_SELECTED) != 0;
        }

        /**
         * Returns true if this node has currently been activated by the user.
         */
        public boolean isActivated() {
            return (mFlags&ViewNode.FLAGS_ACTIVATED) != 0;
        }

        /**
         * Returns true if this node is something the user can perform a long click/press on.
         */
        public boolean isLongClickable() {
            return (mFlags&ViewNode.FLAGS_LONG_CLICKABLE) != 0;
        }

        /**
         * Returns true if this node is something the user can perform a context click on.
         */
        public boolean isContextClickable() {
            return (mFlags&ViewNode.FLAGS_CONTEXT_CLICKABLE) != 0;
        }

        /**
         * Returns the class name of the node's implementation, indicating its behavior.
         * For example, a button will report "android.widget.Button" meaning it behaves
         * like a {@link android.widget.Button}.
         */
        public String getClassName() {
            return mClassName;
        }

        /**
         * Returns any content description associated with the node, which semantically describes
         * its purpose for accessibility and other uses.
         */
        public CharSequence getContentDescription() {
            return mContentDescription;
        }

        /**
         * Returns any text associated with the node that is displayed to the user, or null
         * if there is none.
         */
        public CharSequence getText() {
            return mText != null ? mText.mText : null;
        }

        /**
         * If {@link #getText()} is non-null, this is where the current selection starts.
         */
        public int getTextSelectionStart() {
            return mText != null ? mText.mTextSelectionStart : -1;
        }

        /**
         * If {@link #getText()} is non-null, this is where the current selection starts.
         * If there is no selection, returns the same value as {@link #getTextSelectionStart()},
         * indicating the cursor position.
         */
        public int getTextSelectionEnd() {
            return mText != null ? mText.mTextSelectionEnd : -1;
        }

        /**
         * If {@link #getText()} is non-null, this is the main text color associated with it.
         * If there is no text color, {@link #TEXT_COLOR_UNDEFINED} is returned.
         * Note that the text may also contain style spans that modify the color of specific
         * parts of the text.
         */
        public int getTextColor() {
            return mText != null ? mText.mTextColor : TEXT_COLOR_UNDEFINED;
        }

        /**
         * If {@link #getText()} is non-null, this is the main text background color associated
         * with it.
         * If there is no text background color, {@link #TEXT_COLOR_UNDEFINED} is returned.
         * Note that the text may also contain style spans that modify the color of specific
         * parts of the text.
         */
        public int getTextBackgroundColor() {
            return mText != null ? mText.mTextBackgroundColor : TEXT_COLOR_UNDEFINED;
        }

        /**
         * If {@link #getText()} is non-null, this is the main text size (in pixels) associated
         * with it.
         * Note that the text may also contain style spans that modify the size of specific
         * parts of the text.
         */
        public float getTextSize() {
            return mText != null ? mText.mTextSize : 0;
        }

        /**
         * If {@link #getText()} is non-null, this is the main text style associated
         * with it, containing a bit mask of {@link #TEXT_STYLE_BOLD},
         * {@link #TEXT_STYLE_BOLD}, {@link #TEXT_STYLE_STRIKE_THRU}, and/or
         * {@link #TEXT_STYLE_UNDERLINE}.
         * Note that the text may also contain style spans that modify the style of specific
         * parts of the text.
         */
        public int getTextStyle() {
            return mText != null ? mText.mTextStyle : 0;
        }

        /**
         * Return per-line offsets into the text returned by {@link #getText()}.  Each entry
         * in the array is a formatted line of text, and the value it contains is the offset
         * into the text string where that line starts.  May return null if there is no line
         * information.
         */
        public int[] getTextLineCharOffsets() {
            return mText != null ? mText.mLineCharOffsets : null;
        }

        /**
         * Return per-line baselines into the text returned by {@link #getText()}.  Each entry
         * in the array is a formatted line of text, and the value it contains is the baseline
         * where that text appears in the view.  May return null if there is no line
         * information.
         */
        public int[] getTextLineBaselines() {
            return mText != null ? mText.mLineBaselines : null;
        }

        /**
         * Return additional hint text associated with the node; this is typically used with
         * a node that takes user input, describing to the user what the input means.
         */
        public String getHint() {
            return mText != null ? mText.mHint : null;
        }

        /**
         * Return a Bundle containing optional vendor-specific extension information.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * Return the number of children this node has.
         */
        public int getChildCount() {
            return mChildren != null ? mChildren.length : 0;
        }

        /**
         * Return a child of this node, given an index value from 0 to
         * {@link #getChildCount()}-1.
         */
        public ViewNode getChildAt(int index) {
            return mChildren[index];
        }
    }

    static class ViewNodeBuilder extends ViewStructure {
        final AssistStructure mAssist;
        final ViewNode mNode;
        final boolean mAsync;

        ViewNodeBuilder(AssistStructure assist, ViewNode node, boolean async) {
            mAssist = assist;
            mNode = node;
            mAsync = async;
        }

        @Override
        public void setId(int id, String packageName, String typeName, String entryName) {
            mNode.mId = id;
            mNode.mIdPackage = packageName;
            mNode.mIdType = typeName;
            mNode.mIdEntry = entryName;
        }

        @Override
        public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) {
            mNode.mX = left;
            mNode.mY = top;
            mNode.mScrollX = scrollX;
            mNode.mScrollY = scrollY;
            mNode.mWidth = width;
            mNode.mHeight = height;
        }

        @Override
        public void setTransformation(Matrix matrix) {
            if (matrix == null) {
                mNode.mMatrix = null;
            } else {
                mNode.mMatrix = new Matrix(matrix);
            }
        }

        @Override
        public void setElevation(float elevation) {
            mNode.mElevation = elevation;
        }

        @Override
        public void setAlpha(float alpha) {
            mNode.mAlpha = alpha;
        }

        @Override
        public void setVisibility(int visibility) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_VISIBILITY_MASK) | visibility;
        }

        @Override
        public void setAssistBlocked(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_ASSIST_BLOCKED)
                    | (state ? ViewNode.FLAGS_ASSIST_BLOCKED : 0);
        }

        @Override
        public void setEnabled(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_DISABLED)
                    | (state ? 0 : ViewNode.FLAGS_DISABLED);
        }

        @Override
        public void setClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_CLICKABLE)
                    | (state ? ViewNode.FLAGS_CLICKABLE : 0);
        }

        @Override
        public void setLongClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_LONG_CLICKABLE)
                    | (state ? ViewNode.FLAGS_LONG_CLICKABLE : 0);
        }

        @Override
        public void setContextClickable(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_CONTEXT_CLICKABLE)
                    | (state ? ViewNode.FLAGS_CONTEXT_CLICKABLE : 0);
        }

        @Override
        public void setFocusable(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_FOCUSABLE)
                    | (state ? ViewNode.FLAGS_FOCUSABLE : 0);
        }

        @Override
        public void setFocused(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_FOCUSED)
                    | (state ? ViewNode.FLAGS_FOCUSED : 0);
        }

        @Override
        public void setAccessibilityFocused(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_ACCESSIBILITY_FOCUSED)
                    | (state ? ViewNode.FLAGS_ACCESSIBILITY_FOCUSED : 0);
        }

        @Override
        public void setCheckable(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_CHECKABLE)
                    | (state ? ViewNode.FLAGS_CHECKABLE : 0);
        }

        @Override
        public void setChecked(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_CHECKED)
                    | (state ? ViewNode.FLAGS_CHECKED : 0);
        }

        @Override
        public void setSelected(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_SELECTED)
                    | (state ? ViewNode.FLAGS_SELECTED : 0);
        }

        @Override
        public void setActivated(boolean state) {
            mNode.mFlags = (mNode.mFlags&~ViewNode.FLAGS_ACTIVATED)
                    | (state ? ViewNode.FLAGS_ACTIVATED : 0);
        }

        @Override
        public void setClassName(String className) {
            mNode.mClassName = className;
        }

        @Override
        public void setContentDescription(CharSequence contentDescription) {
            mNode.mContentDescription = contentDescription;
        }

        private final ViewNodeText getNodeText() {
            if (mNode.mText != null) {
                return mNode.mText;
            }
            mNode.mText = new ViewNodeText();
            return mNode.mText;
        }

        @Override
        public void setText(CharSequence text) {
            ViewNodeText t = getNodeText();
            t.mText = text;
            t.mTextSelectionStart = t.mTextSelectionEnd = -1;
        }

        @Override
        public void setText(CharSequence text, int selectionStart, int selectionEnd) {
            ViewNodeText t = getNodeText();
            t.mText = text;
            t.mTextSelectionStart = selectionStart;
            t.mTextSelectionEnd = selectionEnd;
        }

        @Override
        public void setTextStyle(float size, int fgColor, int bgColor, int style) {
            ViewNodeText t = getNodeText();
            t.mTextColor = fgColor;
            t.mTextBackgroundColor = bgColor;
            t.mTextSize = size;
            t.mTextStyle = style;
        }

        @Override
        public void setTextLines(int[] charOffsets, int[] baselines) {
            ViewNodeText t = getNodeText();
            t.mLineCharOffsets = charOffsets;
            t.mLineBaselines = baselines;
        }

        @Override
        public void setHint(CharSequence hint) {
            getNodeText().mHint = hint != null ? hint.toString() : null;
        }

        @Override
        public CharSequence getText() {
            return mNode.mText != null ? mNode.mText.mText : null;
        }

        @Override
        public int getTextSelectionStart() {
            return mNode.mText != null ? mNode.mText.mTextSelectionStart : -1;
        }

        @Override
        public int getTextSelectionEnd() {
            return mNode.mText != null ? mNode.mText.mTextSelectionEnd : -1;
        }

        @Override
        public CharSequence getHint() {
            return mNode.mText != null ? mNode.mText.mHint : null;
        }

        @Override
        public Bundle getExtras() {
            if (mNode.mExtras != null) {
                return mNode.mExtras;
            }
            mNode.mExtras = new Bundle();
            return mNode.mExtras;
        }

        @Override
        public boolean hasExtras() {
            return mNode.mExtras != null;
        }

        @Override
        public void setChildCount(int num) {
            mNode.mChildren = new ViewNode[num];
        }

        @Override
        public int addChildCount(int num) {
            if (mNode.mChildren == null) {
                setChildCount(num);
                return 0;
            }
            final int start = mNode.mChildren.length;
            ViewNode[] newArray = new ViewNode[start + num];
            System.arraycopy(mNode.mChildren, 0, newArray, 0, start);
            mNode.mChildren = newArray;
            return start;
        }

        @Override
        public int getChildCount() {
            return mNode.mChildren != null ? mNode.mChildren.length : 0;
        }

        @Override
        public ViewStructure newChild(int index) {
            ViewNode node = new ViewNode();
            mNode.mChildren[index] = node;
            return new ViewNodeBuilder(mAssist, node, false);
        }

        @Override
        public ViewStructure asyncNewChild(int index) {
            synchronized (mAssist) {
                ViewNode node = new ViewNode();
                mNode.mChildren[index] = node;
                ViewNodeBuilder builder = new ViewNodeBuilder(mAssist, node, true);
                mAssist.mPendingAsyncChildren.add(builder);
                return builder;
            }
        }

        @Override
        public void asyncCommit() {
            synchronized (mAssist) {
                if (!mAsync) {
                    throw new IllegalStateException("Child " + this
                            + " was not created with ViewStructure.asyncNewChild");
                }
                if (!mAssist.mPendingAsyncChildren.remove(this)) {
                    throw new IllegalStateException("Child " + this + " already committed");
                }
                mAssist.notifyAll();
            }
        }

        @Override
        public Rect getTempRect() {
            return mAssist.mTmpRect;
        }
    }

    /** @hide */
    public AssistStructure(Activity activity) {
        mHaveData = true;
        mActivityComponent = activity.getComponentName();
        ArrayList<ViewRootImpl> views = WindowManagerGlobal.getInstance().getRootViews(
                activity.getActivityToken());
        for (int i=0; i<views.size(); i++) {
            ViewRootImpl root = views.get(i);
            mWindowNodes.add(new WindowNode(this, root));
        }
    }

    public AssistStructure() {
        mHaveData = true;
        mActivityComponent = null;
    }

    /** @hide */
    public AssistStructure(Parcel in) {
        mReceiveChannel = in.readStrongBinder();
    }

    /** @hide */
    public void dump() {
        Log.i(TAG, "Activity: " + mActivityComponent.flattenToShortString());
        final int N = getWindowNodeCount();
        for (int i=0; i<N; i++) {
            WindowNode node = getWindowNodeAt(i);
            Log.i(TAG, "Window #" + i + " [" + node.getLeft() + "," + node.getTop()
                    + " " + node.getWidth() + "x" + node.getHeight() + "]" + " " + node.getTitle());
            dump("  ", node.getRootViewNode());
        }
    }

    void dump(String prefix, ViewNode node) {
        Log.i(TAG, prefix + "View [" + node.getLeft() + "," + node.getTop()
                + " " + node.getWidth() + "x" + node.getHeight() + "]" + " " + node.getClassName());
        int id = node.getId();
        if (id != 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix); sb.append("  ID: #"); sb.append(Integer.toHexString(id));
            String entry = node.getIdEntry();
            if (entry != null) {
                String type = node.getIdType();
                String pkg = node.getIdPackage();
                sb.append(" "); sb.append(pkg); sb.append(":"); sb.append(type);
                sb.append("/"); sb.append(entry);
            }
            Log.i(TAG, sb.toString());
        }
        int scrollX = node.getScrollX();
        int scrollY = node.getScrollY();
        if (scrollX != 0 || scrollY != 0) {
            Log.i(TAG, prefix + "  Scroll: " + scrollX + "," + scrollY);
        }
        Matrix matrix = node.getTransformation();
        if (matrix != null) {
            Log.i(TAG, prefix + "  Transformation: " + matrix);
        }
        float elevation = node.getElevation();
        if (elevation != 0) {
            Log.i(TAG, prefix + "  Elevation: " + elevation);
        }
        float alpha = node.getAlpha();
        if (alpha != 0) {
            Log.i(TAG, prefix + "  Alpha: " + elevation);
        }
        CharSequence contentDescription = node.getContentDescription();
        if (contentDescription != null) {
            Log.i(TAG, prefix + "  Content description: " + contentDescription);
        }
        CharSequence text = node.getText();
        if (text != null) {
            Log.i(TAG, prefix + "  Text (sel " + node.getTextSelectionStart() + "-"
                    + node.getTextSelectionEnd() + "): " + text);
            Log.i(TAG, prefix + "  Text size: " + node.getTextSize() + " , style: #"
                    + node.getTextStyle());
            Log.i(TAG, prefix + "  Text color fg: #" + Integer.toHexString(node.getTextColor())
                    + ", bg: #" + Integer.toHexString(node.getTextBackgroundColor()));
        }
        String hint = node.getHint();
        if (hint != null) {
            Log.i(TAG, prefix + "  Hint: " + hint);
        }
        Bundle extras = node.getExtras();
        if (extras != null) {
            Log.i(TAG, prefix + "  Extras: " + extras);
        }
        if (node.isAssistBlocked()) {
            Log.i(TAG, prefix + "  BLOCKED");
        }
        final int NCHILDREN = node.getChildCount();
        if (NCHILDREN > 0) {
            Log.i(TAG, prefix + "  Children:");
            String cprefix = prefix + "    ";
            for (int i=0; i<NCHILDREN; i++) {
                ViewNode cnode = node.getChildAt(i);
                dump(cprefix, cnode);
            }
        }
    }

    /**
     * Return the activity this AssistStructure came from.
     */
    public ComponentName getActivityComponent() {
        ensureData();
        return mActivityComponent;
    }

    /**
     * Return the number of window contents that have been collected in this assist data.
     */
    public int getWindowNodeCount() {
        ensureData();
        return mWindowNodes.size();
    }

    /**
     * Return one of the windows in the assist data.
     * @param index Which window to retrieve, may be 0 to {@link #getWindowNodeCount()}-1.
     */
    public WindowNode getWindowNodeAt(int index) {
        ensureData();
        return mWindowNodes.get(index);
    }

    /** @hide */
    public void ensureData() {
        if (mHaveData) {
            return;
        }
        mHaveData = true;
        ParcelTransferReader reader = new ParcelTransferReader(mReceiveChannel);
        reader.go();
    }

    boolean waitForReady() {
        boolean skipStructure = false;
        synchronized (this) {
            long endTime = SystemClock.uptimeMillis() + 5000;
            long now;
            while (mPendingAsyncChildren.size() > 0 && (now=SystemClock.uptimeMillis()) < endTime) {
                try {
                    wait(endTime-now);
                } catch (InterruptedException e) {
                }
            }
            if (mPendingAsyncChildren.size() > 0) {
                // We waited too long, assume none of the assist structure is valid.
                Log.w(TAG, "Skipping assist structure, waiting too long for async children (have "
                        + mPendingAsyncChildren.size() + " remaining");
                skipStructure = true;
            }
        }
        return !skipStructure;
    }

    /** @hide */
    public void clearSendChannel() {
        if (mSendChannel != null) {
            mSendChannel.mAssistStructure = null;
        }
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        if (mHaveData) {
            // This object holds its data.  We want to write a send channel that the
            // other side can use to retrieve that data.
            if (mSendChannel == null) {
                mSendChannel = new SendChannel(this);
            }
            out.writeStrongBinder(mSendChannel);
        } else {
            // This object doesn't hold its data, so just propagate along its receive channel.
            out.writeStrongBinder(mReceiveChannel);
        }
    }

    public static final Parcelable.Creator<AssistStructure> CREATOR
            = new Parcelable.Creator<AssistStructure>() {
        public AssistStructure createFromParcel(Parcel in) {
            return new AssistStructure(in);
        }

        public AssistStructure[] newArray(int size) {
            return new AssistStructure[size];
        }
    };
}

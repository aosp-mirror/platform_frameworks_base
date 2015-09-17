package android.view;

import android.app.IActivityManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import com.android.internal.view.IDropPermissionHolder;

import java.util.ArrayList;

public class DropPermissionHolder implements Parcelable {

    IDropPermissionHolder mDropPermissionHolder;

    /**
     * Create a new DropPermissionHolder to be passed to the client with a DragEvent.
     *
     * @hide
     */
    public DropPermissionHolder(ClipData clipData, IActivityManager activityManager,
            int sourceUid, String targetPackage, int mode, int sourceUserId, int targetUserId) {
        mDropPermissionHolder = new LocalDropPermissionHolder(clipData, activityManager,
                sourceUid, targetPackage, mode, sourceUserId, targetUserId);
    }

    private class LocalDropPermissionHolder extends IDropPermissionHolder.Stub {

        private final IActivityManager mActivityManager;
        private final int mSourceUid;
        private final String mTargetPackage;
        private final int mMode;
        private final int mSourceUserId;
        private final int mTargetUserId;

        IBinder mPermissionOwner = null;

        final private ArrayList<Uri> mUris = new ArrayList<Uri>();

        LocalDropPermissionHolder(ClipData clipData, IActivityManager activityManager,
                int sourceUid, String targetPackage, int mode, int sourceUserId, int targetUserId) {
            mActivityManager = activityManager;
            mSourceUid = sourceUid;
            mTargetPackage = targetPackage;
            mMode = mode;
            mSourceUserId = sourceUserId;
            mTargetUserId = targetUserId;

            int N = clipData.getItemCount();
            for (int i = 0; i != N; ++i) {
                ClipData.Item item = clipData.getItemAt(i);

                if (item.getUri() != null) {
                    mUris.add(item.getUri());
                }

                Intent intent = item.getIntent();
                if (intent != null && intent.getData() != null) {
                    mUris.add(intent.getData());
                }
            }
        }

        @Override
        public void grant() throws RemoteException {
            if (mPermissionOwner != null) {
                return;
            }

            mPermissionOwner = mActivityManager.newUriPermissionOwner("drop");

            long origId = Binder.clearCallingIdentity();
            try {
                for (Uri mUri : mUris) {
                    mActivityManager.grantUriPermissionFromOwner(
                            mPermissionOwner, mSourceUid, mTargetPackage, mUri, mMode,
                            mSourceUserId, mTargetUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }

        }

        @Override
        public void revoke() throws RemoteException {
            if (mPermissionOwner == null) {
                return;
            }

            for (Uri mUri : mUris) {
                mActivityManager.revokeUriPermissionFromOwner(
                        mPermissionOwner, mUri, mMode, mSourceUserId);
            }

            mPermissionOwner = null;
        }
    }

    /**
     * Request permissions granted by the activity which started the drag.
     */
    public void grant() {
        try {
            mDropPermissionHolder.grant();
        } catch (RemoteException e) {
        }
    }

    /**
     * Revoke permissions granted by the {@link #grant()} call.
     */
    public void revoke() {
        try {
            mDropPermissionHolder.revoke();
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns information about the {@link android.os.Parcel} representation of this
     * DropPermissionHolder object.
     * @return Information about the {@link android.os.Parcel} representation.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Creates a {@link android.os.Parcel} object from this DropPermissionHolder object.
     * @param dest A {@link android.os.Parcel} object in which to put the DropPermissionHolder
     *             object.
     * @param flags Flags to store in the Parcel.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mDropPermissionHolder.asBinder());
    }

    DropPermissionHolder(Parcel in) {
        mDropPermissionHolder = IDropPermissionHolder.Stub.asInterface(in.readStrongBinder());
    }

    /**
     * A container for creating a DropPermissionHolder from a Parcel.
     */
    public static final Parcelable.Creator<DropPermissionHolder> CREATOR
            = new Parcelable.Creator<DropPermissionHolder>() {
        public DropPermissionHolder createFromParcel(Parcel in) {
            return new DropPermissionHolder(in);
        }
        public DropPermissionHolder[] newArray(int size) {
            return new DropPermissionHolder[size];
        }
    };
}

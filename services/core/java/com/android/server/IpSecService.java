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
 * limitations under the License.
 */

package com.android.server;

import static android.Manifest.permission.DUMP;
import static android.net.IpSecManager.INVALID_RESOURCE_ID;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.TrafficStats;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import libcore.io.IoUtils;

/**
 * A service to manage multiple clients that want to access the IpSec API. The service is
 * responsible for maintaining a list of clients and managing the resources (and related quotas)
 * that each of them own.
 *
 * <p>Synchronization in IpSecService is done on all entrypoints due to potential race conditions at
 * the kernel/xfrm level. Further, this allows the simplifying assumption to be made that only one
 * thread is ever running at a time.
 *
 * @hide
 */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NETD_SERVICE_NAME = "netd";
    private static final int[] ADDRESS_FAMILIES =
            new int[] {OsConstants.AF_INET, OsConstants.AF_INET6};

    private static final int NETD_FETCH_TIMEOUT_MS = 5000; // ms
    private static final int MAX_PORT_BIND_ATTEMPTS = 10;
    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    static final int FREE_PORT_MIN = 1024; // ports 1-1023 are reserved
    static final int PORT_MAX = 0xFFFF; // ports are an unsigned 16-bit integer

    /* Binder context for this service */
    private final Context mContext;

    /**
     * The next non-repeating global ID for tracking resources between users, this service, and
     * kernel data structures. Accessing this variable is not thread safe, so it is only read or
     * modified within blocks synchronized on IpSecService.this. We want to avoid -1
     * (INVALID_RESOURCE_ID) and 0 (we probably forgot to initialize it).
     */
    @GuardedBy("IpSecService.this")
    private int mNextResourceId = 1;

    interface IpSecServiceConfiguration {
        INetd getNetdInstance() throws RemoteException;

        static IpSecServiceConfiguration GETSRVINSTANCE =
                new IpSecServiceConfiguration() {
                    @Override
                    public INetd getNetdInstance() throws RemoteException {
                        final INetd netd = NetdService.getInstance();
                        if (netd == null) {
                            throw new RemoteException("Failed to Get Netd Instance");
                        }
                        return netd;
                    }
                };
    }

    private final IpSecServiceConfiguration mSrvConfig;
    final UidFdTagger mUidFdTagger;

    /**
     * Interface for user-reference and kernel-resource cleanup.
     *
     * <p>This interface must be implemented for a resource to be reference counted.
     */
    @VisibleForTesting
    public interface IResource {
        /**
         * Invalidates a IResource object, ensuring it is invalid for the purposes of allocating new
         * objects dependent on it.
         *
         * <p>Implementations of this method are expected to remove references to the IResource
         * object from the IpSecService's tracking arrays. The removal from the arrays ensures that
         * the resource is considered invalid for user access or allocation or use in other
         * resources.
         *
         * <p>References to the IResource object may be held by other RefcountedResource objects,
         * and as such, the underlying resources and quota may not be cleaned up.
         */
        void invalidate() throws RemoteException;

        /**
         * Releases underlying resources and related quotas.
         *
         * <p>Implementations of this method are expected to remove all system resources that are
         * tracked by the IResource object. Due to other RefcountedResource objects potentially
         * having references to the IResource object, freeUnderlyingResources may not always be
         * called from releaseIfUnreferencedRecursively().
         */
        void freeUnderlyingResources() throws RemoteException;
    }

    /**
     * RefcountedResource manages references and dependencies in an exclusively acyclic graph.
     *
     * <p>RefcountedResource implements both explicit and implicit resource management. Creating a
     * RefcountedResource object creates an explicit reference that must be freed by calling
     * userRelease(). Additionally, adding this object as a child of another RefcountedResource
     * object will add an implicit reference.
     *
     * <p>Resources are cleaned up when all references, both implicit and explicit, are released
     * (ie, when userRelease() is called and when all parents have called releaseReference() on this
     * object.)
     */
    @VisibleForTesting
    public class RefcountedResource<T extends IResource> implements IBinder.DeathRecipient {
        private final T mResource;
        private final List<RefcountedResource> mChildren;
        int mRefCount = 1; // starts at 1 for user's reference.
        IBinder mBinder;

        RefcountedResource(T resource, IBinder binder, RefcountedResource... children) {
            synchronized (IpSecService.this) {
                this.mResource = resource;
                this.mChildren = new ArrayList<>(children.length);
                this.mBinder = binder;

                for (RefcountedResource child : children) {
                    mChildren.add(child);
                    child.mRefCount++;
                }

                try {
                    mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                }
            }
        }

        /**
         * If the Binder object dies, this function is called to free the system resources that are
         * being tracked by this record and to subsequently release this record for garbage
         * collection
         */
        @Override
        public void binderDied() {
            synchronized (IpSecService.this) {
                try {
                    userRelease();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to release resource: " + e);
                }
            }
        }

        public T getResource() {
            return mResource;
        }

        /**
         * Unlinks from binder and performs IpSecService resource cleanup (removes from resource
         * arrays)
         *
         * <p>If this method has been previously called, the RefcountedResource's binder field will
         * be null, and the method will return without performing the cleanup a second time.
         *
         * <p>Note that calling this function does not imply that kernel resources will be freed at
         * this time, or that the related quota will be returned. Such actions will only be
         * performed upon the reference count reaching zero.
         */
        @GuardedBy("IpSecService.this")
        public void userRelease() throws RemoteException {
            // Prevent users from putting reference counts into a bad state by calling
            // userRelease() multiple times.
            if (mBinder == null) {
                return;
            }

            mBinder.unlinkToDeath(this, 0);
            mBinder = null;

            mResource.invalidate();

            releaseReference();
        }

        /**
         * Removes a reference to this resource. If the resultant reference count is zero, the
         * underlying resources are freed, and references to all child resources are also dropped
         * recursively (resulting in them freeing their resources and children, etcetera)
         *
         * <p>This method also sets the reference count to an invalid value (-1) to signify that it
         * has been fully released. Any subsequent calls to this method will result in an
         * IllegalStateException being thrown due to resource already having been previously
         * released
         */
        @VisibleForTesting
        @GuardedBy("IpSecService.this")
        public void releaseReference() throws RemoteException {
            mRefCount--;

            if (mRefCount > 0) {
                return;
            } else if (mRefCount < 0) {
                throw new IllegalStateException(
                        "Invalid operation - resource has already been released.");
            }

            // Cleanup own resources
            mResource.freeUnderlyingResources();

            // Cleanup child resources as needed
            for (RefcountedResource<? extends IResource> child : mChildren) {
                child.releaseReference();
            }

            // Enforce that resource cleanup can only be called once
            // By decrementing the refcount (from 0 to -1), the next call will throw an
            // IllegalStateException - it has already been released fully.
            mRefCount--;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mResource=")
                    .append(mResource)
                    .append(", mRefCount=")
                    .append(mRefCount)
                    .append(", mChildren=")
                    .append(mChildren)
                    .append("}")
                    .toString();
        }
    }

    /**
     * Very simple counting class that looks much like a counting semaphore
     *
     * <p>This class is not thread-safe, and expects that that users of this class will ensure
     * synchronization and thread safety by holding the IpSecService.this instance lock.
     */
    @VisibleForTesting
    static class ResourceTracker {
        private final int mMax;
        int mCurrent;

        ResourceTracker(int max) {
            mMax = max;
            mCurrent = 0;
        }

        boolean isAvailable() {
            return (mCurrent < mMax);
        }

        void take() {
            if (!isAvailable()) {
                Log.wtf(TAG, "Too many resources allocated!");
            }
            mCurrent++;
        }

        void give() {
            if (mCurrent <= 0) {
                Log.wtf(TAG, "We've released this resource too many times");
            }
            mCurrent--;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mCurrent=")
                    .append(mCurrent)
                    .append(", mMax=")
                    .append(mMax)
                    .append("}")
                    .toString();
        }
    }

    @VisibleForTesting
    static final class UserRecord {
        /* Maximum number of each type of resource that a single UID may possess */
        public static final int MAX_NUM_TUNNEL_INTERFACES = 2;
        public static final int MAX_NUM_ENCAP_SOCKETS = 2;
        public static final int MAX_NUM_TRANSFORMS = 4;
        public static final int MAX_NUM_SPIS = 8;

        /**
         * Store each of the OwnedResource types in an (thinly wrapped) sparse array for indexing
         * and explicit (user) reference management.
         *
         * <p>These are stored in separate arrays to improve debuggability and dump output clarity.
         *
         * <p>Resources are removed from this array when the user releases their explicit reference
         * by calling one of the releaseResource() methods.
         */
        final RefcountedResourceArray<SpiRecord> mSpiRecords =
                new RefcountedResourceArray<>(SpiRecord.class.getSimpleName());
        final RefcountedResourceArray<TransformRecord> mTransformRecords =
                new RefcountedResourceArray<>(TransformRecord.class.getSimpleName());
        final RefcountedResourceArray<EncapSocketRecord> mEncapSocketRecords =
                new RefcountedResourceArray<>(EncapSocketRecord.class.getSimpleName());
        final RefcountedResourceArray<TunnelInterfaceRecord> mTunnelInterfaceRecords =
                new RefcountedResourceArray<>(TunnelInterfaceRecord.class.getSimpleName());

        /**
         * Trackers for quotas for each of the OwnedResource types.
         *
         * <p>These trackers are separate from the resource arrays, since they are incremented and
         * decremented at different points in time. Specifically, quota is only returned upon final
         * resource deallocation (after all explicit and implicit references are released). Note
         * that it is possible that calls to releaseResource() will not return the used quota if
         * there are other resources that depend on (are parents of) the resource being released.
         */
        final ResourceTracker mSpiQuotaTracker = new ResourceTracker(MAX_NUM_SPIS);
        final ResourceTracker mTransformQuotaTracker = new ResourceTracker(MAX_NUM_TRANSFORMS);
        final ResourceTracker mSocketQuotaTracker = new ResourceTracker(MAX_NUM_ENCAP_SOCKETS);
        final ResourceTracker mTunnelQuotaTracker = new ResourceTracker(MAX_NUM_TUNNEL_INTERFACES);

        void removeSpiRecord(int resourceId) {
            mSpiRecords.remove(resourceId);
        }

        void removeTransformRecord(int resourceId) {
            mTransformRecords.remove(resourceId);
        }

        void removeTunnelInterfaceRecord(int resourceId) {
            mTunnelInterfaceRecords.remove(resourceId);
        }

        void removeEncapSocketRecord(int resourceId) {
            mEncapSocketRecords.remove(resourceId);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mSpiQuotaTracker=")
                    .append(mSpiQuotaTracker)
                    .append(", mTransformQuotaTracker=")
                    .append(mTransformQuotaTracker)
                    .append(", mSocketQuotaTracker=")
                    .append(mSocketQuotaTracker)
                    .append(", mTunnelQuotaTracker=")
                    .append(mTunnelQuotaTracker)
                    .append(", mSpiRecords=")
                    .append(mSpiRecords)
                    .append(", mTransformRecords=")
                    .append(mTransformRecords)
                    .append(", mEncapSocketRecords=")
                    .append(mEncapSocketRecords)
                    .append(", mTunnelInterfaceRecords=")
                    .append(mTunnelInterfaceRecords)
                    .append("}")
                    .toString();
        }
    }

    /**
     * This class is not thread-safe, and expects that that users of this class will ensure
     * synchronization and thread safety by holding the IpSecService.this instance lock.
     */
    @VisibleForTesting
    static final class UserResourceTracker {
        private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();

        /** Lazy-initialization/getter that populates or retrieves the UserRecord as needed */
        public UserRecord getUserRecord(int uid) {
            checkCallerUid(uid);

            UserRecord r = mUserRecords.get(uid);
            if (r == null) {
                r = new UserRecord();
                mUserRecords.put(uid, r);
            }
            return r;
        }

        /** Safety method; guards against access of other user's UserRecords */
        private void checkCallerUid(int uid) {
            if (uid != Binder.getCallingUid()
                    && android.os.Process.SYSTEM_UID != Binder.getCallingUid()) {
                throw new SecurityException("Attempted access of unowned resources");
            }
        }

        @Override
        public String toString() {
            return mUserRecords.toString();
        }
    }

    @VisibleForTesting final UserResourceTracker mUserResourceTracker = new UserResourceTracker();

    /**
     * The OwnedResourceRecord class provides a facility to cleanly and reliably track system
     * resources. It relies on a provided resourceId that should uniquely identify the kernel
     * resource. To use this class, the user should implement the invalidate() and
     * freeUnderlyingResources() methods that are responsible for cleaning up IpSecService resource
     * tracking arrays and kernel resources, respectively.
     *
     * <p>This class associates kernel resources with the UID that owns and controls them.
     */
    private abstract class OwnedResourceRecord implements IResource {
        final int pid;
        final int uid;
        protected final int mResourceId;

        OwnedResourceRecord(int resourceId) {
            super();
            if (resourceId == INVALID_RESOURCE_ID) {
                throw new IllegalArgumentException("Resource ID must not be INVALID_RESOURCE_ID");
            }
            mResourceId = resourceId;
            pid = Binder.getCallingPid();
            uid = Binder.getCallingUid();

            getResourceTracker().take();
        }

        @Override
        public abstract void invalidate() throws RemoteException;

        /** Convenience method; retrieves the user resource record for the stored UID. */
        protected UserRecord getUserRecord() {
            return mUserResourceTracker.getUserRecord(uid);
        }

        @Override
        public abstract void freeUnderlyingResources() throws RemoteException;

        /** Get the resource tracker for this resource */
        protected abstract ResourceTracker getResourceTracker();

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{mResourceId=")
                    .append(mResourceId)
                    .append(", pid=")
                    .append(pid)
                    .append(", uid=")
                    .append(uid)
                    .append("}")
                    .toString();
        }
    };

    /**
     * Thin wrapper over SparseArray to ensure resources exist, and simplify generic typing.
     *
     * <p>RefcountedResourceArray prevents null insertions, and throws an IllegalArgumentException
     * if a key is not found during a retrieval process.
     */
    static class RefcountedResourceArray<T extends IResource> {
        SparseArray<RefcountedResource<T>> mArray = new SparseArray<>();
        private final String mTypeName;

        public RefcountedResourceArray(String typeName) {
            this.mTypeName = typeName;
        }

        /**
         * Accessor method to get inner resource object.
         *
         * @throws IllegalArgumentException if no resource with provided key is found.
         */
        T getResourceOrThrow(int key) {
            return getRefcountedResourceOrThrow(key).getResource();
        }

        /**
         * Accessor method to get reference counting wrapper.
         *
         * @throws IllegalArgumentException if no resource with provided key is found.
         */
        RefcountedResource<T> getRefcountedResourceOrThrow(int key) {
            RefcountedResource<T> resource = mArray.get(key);
            if (resource == null) {
                throw new IllegalArgumentException(
                        String.format("No such %s found for given id: %d", mTypeName, key));
            }

            return resource;
        }

        void put(int key, RefcountedResource<T> obj) {
            checkNotNull(obj, "Null resources cannot be added");
            mArray.put(key, obj);
        }

        void remove(int key) {
            mArray.remove(key);
        }

        @Override
        public String toString() {
            return mArray.toString();
        }
    }

    /**
     * Tracks an SA in the kernel, and manages cleanup paths. Once a TransformRecord is
     * created, the SpiRecord that originally tracked the SAs will reliquish the
     * responsibility of freeing the underlying SA to this class via the mOwnedByTransform flag.
     */
    private final class TransformRecord extends OwnedResourceRecord {
        private final IpSecConfig mConfig;
        private final SpiRecord mSpi;
        private final EncapSocketRecord mSocket;

        TransformRecord(
                int resourceId, IpSecConfig config, SpiRecord spi, EncapSocketRecord socket) {
            super(resourceId);
            mConfig = config;
            mSpi = spi;
            mSocket = socket;

            spi.setOwnedByTransform();
        }

        public IpSecConfig getConfig() {
            return mConfig;
        }

        public SpiRecord getSpiRecord() {
            return mSpi;
        }

        public EncapSocketRecord getSocketRecord() {
            return mSocket;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            int spi = mSpi.getSpi();
            try {
                mSrvConfig
                        .getNetdInstance()
                        .ipSecDeleteSecurityAssociation(
                                uid,
                                mConfig.getSourceAddress(),
                                mConfig.getDestinationAddress(),
                                spi,
                                mConfig.getMarkValue(),
                                mConfig.getMarkMask());
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Failed to delete SA with ID: " + mResourceId, e);
            }

            getResourceTracker().give();
        }

        @Override
        public void invalidate() throws RemoteException {
            getUserRecord().removeTransformRecord(mResourceId);
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mTransformQuotaTracker;
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mSpi.mResourceId=")
                    .append(mSpi.mResourceId)
                    .append(", mConfig=")
                    .append(mConfig)
                    .append("}");
            return strBuilder.toString();
        }
    }

    /**
     * Tracks a single SA in the kernel, and manages cleanup paths. Once used in a Transform, the
     * responsibility for cleaning up underlying resources will be passed to the TransformRecord
     * object
     */
    private final class SpiRecord extends OwnedResourceRecord {
        private final String mSourceAddress;
        private final String mDestinationAddress;
        private int mSpi;

        private boolean mOwnedByTransform = false;

        SpiRecord(int resourceId, String sourceAddress, String destinationAddress, int spi) {
            super(resourceId);
            mSourceAddress = sourceAddress;
            mDestinationAddress = destinationAddress;
            mSpi = spi;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            try {
                if (!mOwnedByTransform) {
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecDeleteSecurityAssociation(
                                    uid, mSourceAddress, mDestinationAddress, mSpi, 0, 0);
                }
            } catch (ServiceSpecificException | RemoteException e) {
                Log.e(TAG, "Failed to delete SPI reservation with ID: " + mResourceId, e);
            }

            mSpi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;

            getResourceTracker().give();
        }

        public int getSpi() {
            return mSpi;
        }

        public String getDestinationAddress() {
            return mDestinationAddress;
        }

        public void setOwnedByTransform() {
            if (mOwnedByTransform) {
                // Programming error
                throw new IllegalStateException("Cannot own an SPI twice!");
            }

            mOwnedByTransform = true;
        }

        public boolean getOwnedByTransform() {
            return mOwnedByTransform;
        }

        @Override
        public void invalidate() throws RemoteException {
            getUserRecord().removeSpiRecord(mResourceId);
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSpiQuotaTracker;
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            strBuilder
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSpi=")
                    .append(mSpi)
                    .append(", mSourceAddress=")
                    .append(mSourceAddress)
                    .append(", mDestinationAddress=")
                    .append(mDestinationAddress)
                    .append(", mOwnedByTransform=")
                    .append(mOwnedByTransform)
                    .append("}");
            return strBuilder.toString();
        }
    }

    // These values have been reserved in ConnectivityService
    @VisibleForTesting static final int TUN_INTF_NETID_START = 0xFC00;

    @VisibleForTesting static final int TUN_INTF_NETID_RANGE = 0x0400;

    private final SparseBooleanArray mTunnelNetIds = new SparseBooleanArray();
    private int mNextTunnelNetIdIndex = 0;

    /**
     * Reserves a netId within the range of netIds allocated for IPsec tunnel interfaces
     *
     * <p>This method should only be called from Binder threads. Do not call this from within the
     * system server as it will crash the system on failure.
     *
     * @return an integer key within the netId range, if successful
     * @throws IllegalStateException if unsuccessful (all netId are currently reserved)
     */
    @VisibleForTesting
    int reserveNetId() {
        synchronized (mTunnelNetIds) {
            for (int i = 0; i < TUN_INTF_NETID_RANGE; i++) {
                int index = mNextTunnelNetIdIndex;
                int netId = index + TUN_INTF_NETID_START;
                if (++mNextTunnelNetIdIndex >= TUN_INTF_NETID_RANGE) mNextTunnelNetIdIndex = 0;
                if (!mTunnelNetIds.get(netId)) {
                    mTunnelNetIds.put(netId, true);
                    return netId;
                }
            }
        }
        throw new IllegalStateException("No free netIds to allocate");
    }

    @VisibleForTesting
    void releaseNetId(int netId) {
        synchronized (mTunnelNetIds) {
            mTunnelNetIds.delete(netId);
        }
    }

    private final class TunnelInterfaceRecord extends OwnedResourceRecord {
        private final String mInterfaceName;
        private final Network mUnderlyingNetwork;

        // outer addresses
        private final String mLocalAddress;
        private final String mRemoteAddress;

        private final int mIkey;
        private final int mOkey;

        TunnelInterfaceRecord(
                int resourceId,
                String interfaceName,
                Network underlyingNetwork,
                String localAddr,
                String remoteAddr,
                int ikey,
                int okey) {
            super(resourceId);

            mInterfaceName = interfaceName;
            mUnderlyingNetwork = underlyingNetwork;
            mLocalAddress = localAddr;
            mRemoteAddress = remoteAddr;
            mIkey = ikey;
            mOkey = okey;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            // Calls to netd
            //       Teardown VTI
            //       Delete global policies
            try {
                final INetd netd = mSrvConfig.getNetdInstance();
                netd.removeVirtualTunnelInterface(mInterfaceName);

                for (int selAddrFamily : ADDRESS_FAMILIES) {
                    netd.ipSecDeleteSecurityPolicy(
                            uid,
                            selAddrFamily,
                            IpSecManager.DIRECTION_OUT,
                            mOkey,
                            0xffffffff);
                    netd.ipSecDeleteSecurityPolicy(
                            uid,
                            selAddrFamily,
                            IpSecManager.DIRECTION_IN,
                            mIkey,
                            0xffffffff);
                }
            } catch (ServiceSpecificException | RemoteException e) {
                Log.e(
                        TAG,
                        "Failed to delete VTI with interface name: "
                                + mInterfaceName
                                + " and id: "
                                + mResourceId, e);
            }

            getResourceTracker().give();
            releaseNetId(mIkey);
            releaseNetId(mOkey);
        }

        public String getInterfaceName() {
            return mInterfaceName;
        }

        public Network getUnderlyingNetwork() {
            return mUnderlyingNetwork;
        }

        /** Returns the local, outer address for the tunnelInterface */
        public String getLocalAddress() {
            return mLocalAddress;
        }

        /** Returns the remote, outer address for the tunnelInterface */
        public String getRemoteAddress() {
            return mRemoteAddress;
        }

        public int getIkey() {
            return mIkey;
        }

        public int getOkey() {
            return mOkey;
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mTunnelQuotaTracker;
        }

        @Override
        public void invalidate() {
            getUserRecord().removeTunnelInterfaceRecord(mResourceId);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{super=")
                    .append(super.toString())
                    .append(", mInterfaceName=")
                    .append(mInterfaceName)
                    .append(", mUnderlyingNetwork=")
                    .append(mUnderlyingNetwork)
                    .append(", mLocalAddress=")
                    .append(mLocalAddress)
                    .append(", mRemoteAddress=")
                    .append(mRemoteAddress)
                    .append(", mIkey=")
                    .append(mIkey)
                    .append(", mOkey=")
                    .append(mOkey)
                    .append("}")
                    .toString();
        }
    }

    /**
     * Tracks a UDP encap socket, and manages cleanup paths
     *
     * <p>While this class does not manage non-kernel resources, race conditions around socket
     * binding require that the service creates the encap socket, binds it and applies the socket
     * policy before handing it to a user.
     */
    private final class EncapSocketRecord extends OwnedResourceRecord {
        private FileDescriptor mSocket;
        private final int mPort;

        EncapSocketRecord(int resourceId, FileDescriptor socket, int port) {
            super(resourceId);
            mSocket = socket;
            mPort = port;
        }

        /** always guarded by IpSecService#this */
        @Override
        public void freeUnderlyingResources() {
            Log.d(TAG, "Closing port " + mPort);
            IoUtils.closeQuietly(mSocket);
            mSocket = null;

            getResourceTracker().give();
        }

        public int getPort() {
            return mPort;
        }

        public FileDescriptor getFileDescriptor() {
            return mSocket;
        }

        @Override
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSocketQuotaTracker;
        }

        @Override
        public void invalidate() {
            getUserRecord().removeEncapSocketRecord(mResourceId);
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("{super=")
                    .append(super.toString())
                    .append(", mSocket=")
                    .append(mSocket)
                    .append(", mPort=")
                    .append(mPort)
                    .append("}")
                    .toString();
        }
    }

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        this(context, IpSecServiceConfiguration.GETSRVINSTANCE);
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    @NonNull
    private AppOpsManager getAppOpsManager() {
        AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        if(appOps == null) throw new RuntimeException("System Server couldn't get AppOps");
        return appOps;
    }

    /** @hide */
    @VisibleForTesting
    public IpSecService(Context context, IpSecServiceConfiguration config) {
        this(
                context,
                config,
                (fd, uid) -> {
                    try {
                        TrafficStats.setThreadStatsUid(uid);
                        TrafficStats.tagFileDescriptor(fd);
                    } finally {
                        TrafficStats.clearThreadStatsUid();
                    }
                });
    }

    /** @hide */
    @VisibleForTesting
    public IpSecService(
            Context context, IpSecServiceConfiguration config, UidFdTagger uidFdTagger) {
        mContext = context;
        mSrvConfig = config;
        mUidFdTagger = uidFdTagger;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    private void connectNativeNetdService() {
        // Avoid blocking the system server to do this
        new Thread() {
            @Override
            public void run() {
                synchronized (IpSecService.this) {
                    NetdService.get(NETD_FETCH_TIMEOUT_MS);
                }
            }
        }.start();
    }

    synchronized boolean isNetdAlive() {
        try {
            final INetd netd = mSrvConfig.getNetdInstance();
            if (netd == null) {
                return false;
            }
            return netd.isAlive();
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Checks that the provided InetAddress is valid for use in an IPsec SA. The address must not be
     * a wildcard address and must be in a numeric form such as 1.2.3.4 or 2001::1.
     */
    private static void checkInetAddress(String inetAddress) {
        if (TextUtils.isEmpty(inetAddress)) {
            throw new IllegalArgumentException("Unspecified address");
        }

        InetAddress checkAddr = NetworkUtils.numericToInetAddress(inetAddress);

        if (checkAddr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Inappropriate wildcard address: " + inetAddress);
        }
    }

    /**
     * Checks the user-provided direction field and throws an IllegalArgumentException if it is not
     * DIRECTION_IN or DIRECTION_OUT
     */
    private static void checkDirection(int direction) {
        switch (direction) {
            case IpSecManager.DIRECTION_OUT:
            case IpSecManager.DIRECTION_IN:
                return;
        }
        throw new IllegalArgumentException("Invalid Direction: " + direction);
    }

    /** Get a new SPI and maintain the reservation in the system server */
    @Override
    public synchronized IpSecSpiResponse allocateSecurityParameterIndex(
            String destinationAddress, int requestedSpi, IBinder binder) throws RemoteException {
        checkInetAddress(destinationAddress);
        // RFC 4303 Section 2.1 - 0=local, 1-255=reserved.
        if (requestedSpi > 0 && requestedSpi < 256) {
            throw new IllegalArgumentException("ESP SPI must not be in the range of 0-255.");
        }
        checkNotNull(binder, "Null Binder passed to allocateSecurityParameterIndex");

        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = mUserResourceTracker.getUserRecord(callingUid);
        final int resourceId = mNextResourceId++;

        int spi = IpSecManager.INVALID_SECURITY_PARAMETER_INDEX;
        try {
            if (!userRecord.mSpiQuotaTracker.isAvailable()) {
                return new IpSecSpiResponse(
                        IpSecManager.Status.RESOURCE_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
            }

            spi =
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecAllocateSpi(callingUid, "", destinationAddress, requestedSpi);
            Log.d(TAG, "Allocated SPI " + spi);
            userRecord.mSpiRecords.put(
                    resourceId,
                    new RefcountedResource<SpiRecord>(
                            new SpiRecord(resourceId, "", destinationAddress, spi), binder));
        } catch (ServiceSpecificException e) {
            if (e.errorCode == OsConstants.ENOENT) {
                return new IpSecSpiResponse(
                        IpSecManager.Status.SPI_UNAVAILABLE, INVALID_RESOURCE_ID, spi);
            }
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return new IpSecSpiResponse(IpSecManager.Status.OK, resourceId, spi);
    }

    /* This method should only be called from Binder threads. Do not call this from
     * within the system server as it will crash the system on failure.
     */
    private void releaseResource(RefcountedResourceArray resArray, int resourceId)
            throws RemoteException {
        resArray.getRefcountedResourceOrThrow(resourceId).userRelease();
    }

    /** Release a previously allocated SPI that has been registered with the system server */
    @Override
    public synchronized void releaseSecurityParameterIndex(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mSpiRecords, resourceId);
    }

    /**
     * This function finds and forcibly binds to a random system port, ensuring that the port cannot
     * be unbound.
     *
     * <p>A socket cannot be un-bound from a port if it was bound to that port by number. To select
     * a random open port and then bind by number, this function creates a temp socket, binds to a
     * random port (specifying 0), gets that port number, and then uses is to bind the user's UDP
     * Encapsulation Socket forcibly, so that it cannot be un-bound by the user with the returned
     * FileHandle.
     *
     * <p>The loop in this function handles the inherent race window between un-binding to a port
     * and re-binding, during which the system could *technically* hand that port out to someone
     * else.
     */
    private int bindToRandomPort(FileDescriptor sockFd) throws IOException {
        for (int i = MAX_PORT_BIND_ATTEMPTS; i > 0; i--) {
            try {
                FileDescriptor probeSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
                Os.bind(probeSocket, INADDR_ANY, 0);
                int port = ((InetSocketAddress) Os.getsockname(probeSocket)).getPort();
                Os.close(probeSocket);
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
                return port;
            } catch (ErrnoException e) {
                // Someone miraculously claimed the port just after we closed probeSocket.
                if (e.errno == OsConstants.EADDRINUSE) {
                    continue;
                }
                throw e.rethrowAsIOException();
            }
        }
        throw new IOException("Failed " + MAX_PORT_BIND_ATTEMPTS + " attempts to bind to a port");
    }

    /**
     * Functional interface to do traffic tagging of given sockets to UIDs.
     *
     * <p>Specifically used by openUdpEncapsulationSocket to ensure data usage on the UDP encap
     * sockets are billed to the UID that the UDP encap socket was created on behalf of.
     *
     * <p>Separate class so that the socket tagging logic can be mocked; TrafficStats uses static
     * methods that cannot be easily mocked/tested.
     */
    @VisibleForTesting
    public interface UidFdTagger {
        /**
         * Sets socket tag to assign all traffic to the provided UID.
         *
         * <p>Since the socket is created on behalf of an unprivileged application, all traffic
         * should be accounted to the UID of the unprivileged application.
         */
        public void tag(FileDescriptor fd, int uid) throws IOException;
    }

    /**
     * Open a socket via the system server and bind it to the specified port (random if port=0).
     * This will return a PFD to the user that represent a bound UDP socket. The system server will
     * cache the socket and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public synchronized IpSecUdpEncapResponse openUdpEncapsulationSocket(int port, IBinder binder)
            throws RemoteException {
        if (port != 0 && (port < FREE_PORT_MIN || port > PORT_MAX)) {
            throw new IllegalArgumentException(
                    "Specified port number must be a valid non-reserved UDP port");
        }
        checkNotNull(binder, "Null Binder passed to openUdpEncapsulationSocket");

        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = mUserResourceTracker.getUserRecord(callingUid);
        final int resourceId = mNextResourceId++;
        FileDescriptor sockFd = null;
        try {
            if (!userRecord.mSocketQuotaTracker.isAvailable()) {
                return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
            }

            sockFd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
            mUidFdTagger.tag(sockFd, callingUid);

            // This code is common to both the unspecified and specified port cases
            Os.setsockoptInt(
                    sockFd,
                    OsConstants.IPPROTO_UDP,
                    OsConstants.UDP_ENCAP,
                    OsConstants.UDP_ENCAP_ESPINUDP);

            mSrvConfig.getNetdInstance().ipSecSetEncapSocketOwner(sockFd, callingUid);
            if (port != 0) {
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
            } else {
                port = bindToRandomPort(sockFd);
            }

            userRecord.mEncapSocketRecords.put(
                    resourceId,
                    new RefcountedResource<EncapSocketRecord>(
                            new EncapSocketRecord(resourceId, sockFd, port), binder));
            return new IpSecUdpEncapResponse(IpSecManager.Status.OK, resourceId, port, sockFd);
        } catch (IOException | ErrnoException e) {
            IoUtils.closeQuietly(sockFd);
        }
        // If we make it to here, then something has gone wrong and we couldn't open a socket.
        // The only reasonable condition that would cause that is resource unavailable.
        return new IpSecUdpEncapResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
    }

    /** close a socket that has been been allocated by and registered with the system server */
    @Override
    public synchronized void closeUdpEncapsulationSocket(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mEncapSocketRecords, resourceId);
    }

    /**
     * Create a tunnel interface for use in IPSec tunnel mode. The system server will cache the
     * tunnel interface and a record of its owner so that it can and must be freed when no longer
     * needed.
     */
    @Override
    public synchronized IpSecTunnelInterfaceResponse createTunnelInterface(
            String localAddr, String remoteAddr, Network underlyingNetwork, IBinder binder,
            String callingPackage) {
        enforceTunnelPermissions(callingPackage);
        checkNotNull(binder, "Null Binder passed to createTunnelInterface");
        checkNotNull(underlyingNetwork, "No underlying network was specified");
        checkInetAddress(localAddr);
        checkInetAddress(remoteAddr);

        // TODO: Check that underlying network exists, and IP addresses not assigned to a different
        //       network (b/72316676).

        int callerUid = Binder.getCallingUid();
        UserRecord userRecord = mUserResourceTracker.getUserRecord(callerUid);
        if (!userRecord.mTunnelQuotaTracker.isAvailable()) {
            return new IpSecTunnelInterfaceResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
        }

        final int resourceId = mNextResourceId++;
        final int ikey = reserveNetId();
        final int okey = reserveNetId();
        String intfName = String.format("%s%d", INetd.IPSEC_INTERFACE_PREFIX, resourceId);

        try {
            // Calls to netd:
            //       Create VTI
            //       Add inbound/outbound global policies
            //              (use reqid = 0)
            final INetd netd = mSrvConfig.getNetdInstance();
            netd.addVirtualTunnelInterface(intfName, localAddr, remoteAddr, ikey, okey);

            for (int selAddrFamily : ADDRESS_FAMILIES) {
                // Always send down correct local/remote addresses for template.
                netd.ipSecAddSecurityPolicy(
                        callerUid,
                        selAddrFamily,
                        IpSecManager.DIRECTION_OUT,
                        localAddr,
                        remoteAddr,
                        0,
                        okey,
                        0xffffffff);
                netd.ipSecAddSecurityPolicy(
                        callerUid,
                        selAddrFamily,
                        IpSecManager.DIRECTION_IN,
                        remoteAddr,
                        localAddr,
                        0,
                        ikey,
                        0xffffffff);
            }

            userRecord.mTunnelInterfaceRecords.put(
                    resourceId,
                    new RefcountedResource<TunnelInterfaceRecord>(
                            new TunnelInterfaceRecord(
                                    resourceId,
                                    intfName,
                                    underlyingNetwork,
                                    localAddr,
                                    remoteAddr,
                                    ikey,
                                    okey),
                            binder));
            return new IpSecTunnelInterfaceResponse(IpSecManager.Status.OK, resourceId, intfName);
        } catch (RemoteException e) {
            // Release keys if we got an error.
            releaseNetId(ikey);
            releaseNetId(okey);
            throw e.rethrowFromSystemServer();
        } catch (Throwable t) {
            // Release keys if we got an error.
            releaseNetId(ikey);
            releaseNetId(okey);
            throw t;
        }
    }

    /**
     * Adds a new local address to the tunnel interface. This allows packets to be sent and received
     * from multiple local IP addresses over the same tunnel.
     */
    @Override
    public synchronized void addAddressToTunnelInterface(
            int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelPermissions(callingPackage);
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());

        // Get tunnelInterface record; if no such interface is found, will throw
        // IllegalArgumentException
        TunnelInterfaceRecord tunnelInterfaceInfo =
                userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);

        try {
            // We can assume general validity of the IP address, since we get them as a
            // LinkAddress, which does some validation.
            mSrvConfig
                    .getNetdInstance()
                    .interfaceAddAddress(
                            tunnelInterfaceInfo.mInterfaceName,
                            localAddr.getAddress().getHostAddress(),
                            localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a new local address from the tunnel interface. After removal, the address will no
     * longer be available to send from, or receive on.
     */
    @Override
    public synchronized void removeAddressFromTunnelInterface(
            int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelPermissions(callingPackage);

        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        // Get tunnelInterface record; if no such interface is found, will throw
        // IllegalArgumentException
        TunnelInterfaceRecord tunnelInterfaceInfo =
                userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);

        try {
            // We can assume general validity of the IP address, since we get them as a
            // LinkAddress, which does some validation.
            mSrvConfig
                    .getNetdInstance()
                    .interfaceDelAddress(
                            tunnelInterfaceInfo.mInterfaceName,
                            localAddr.getAddress().getHostAddress(),
                            localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Delete a TunnelInterface that has been been allocated by and registered with the system
     * server
     */
    @Override
    public synchronized void deleteTunnelInterface(
            int resourceId, String callingPackage) throws RemoteException {
        enforceTunnelPermissions(callingPackage);
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTunnelInterfaceRecords, resourceId);
    }

    @VisibleForTesting
    void validateAlgorithms(IpSecConfig config) throws IllegalArgumentException {
        IpSecAlgorithm auth = config.getAuthentication();
        IpSecAlgorithm crypt = config.getEncryption();
        IpSecAlgorithm aead = config.getAuthenticatedEncryption();

        // Validate the algorithm set
        Preconditions.checkArgument(
                aead != null || crypt != null || auth != null,
                "No Encryption or Authentication algorithms specified");
        Preconditions.checkArgument(
                auth == null || auth.isAuthentication(),
                "Unsupported algorithm for Authentication");
        Preconditions.checkArgument(
                crypt == null || crypt.isEncryption(), "Unsupported algorithm for Encryption");
        Preconditions.checkArgument(
                aead == null || aead.isAead(),
                "Unsupported algorithm for Authenticated Encryption");
        Preconditions.checkArgument(
                aead == null || (auth == null && crypt == null),
                "Authenticated Encryption is mutually exclusive with other Authentication "
                        + "or Encryption algorithms");
    }

    /**
     * Checks an IpSecConfig parcel to ensure that the contents are sane and throws an
     * IllegalArgumentException if they are not.
     */
    private void checkIpSecConfig(IpSecConfig config) {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());

        switch (config.getEncapType()) {
            case IpSecTransform.ENCAP_NONE:
                break;
            case IpSecTransform.ENCAP_ESPINUDP:
            case IpSecTransform.ENCAP_ESPINUDP_NON_IKE:
                // Retrieve encap socket record; will throw IllegalArgumentException if not found
                userRecord.mEncapSocketRecords.getResourceOrThrow(
                        config.getEncapSocketResourceId());

                int port = config.getEncapRemotePort();
                if (port <= 0 || port > 0xFFFF) {
                    throw new IllegalArgumentException("Invalid remote UDP port: " + port);
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid Encap Type: " + config.getEncapType());
        }

        validateAlgorithms(config);

        // Retrieve SPI record; will throw IllegalArgumentException if not found
        SpiRecord s = userRecord.mSpiRecords.getResourceOrThrow(config.getSpiResourceId());

        // Check to ensure that SPI has not already been used.
        if (s.getOwnedByTransform()) {
            throw new IllegalStateException("SPI already in use; cannot be used in new Transforms");
        }

        // If no remote address is supplied, then use one from the SPI.
        if (TextUtils.isEmpty(config.getDestinationAddress())) {
            config.setDestinationAddress(s.getDestinationAddress());
        }

        // All remote addresses must match
        if (!config.getDestinationAddress().equals(s.getDestinationAddress())) {
            throw new IllegalArgumentException("Mismatched remote addresseses.");
        }

        // This check is technically redundant due to the chain of custody between the SPI and
        // the IpSecConfig, but in the future if the dest is allowed to be set explicitly in
        // the transform, this will prevent us from messing up.
        checkInetAddress(config.getDestinationAddress());

        // Require a valid source address for all transforms.
        checkInetAddress(config.getSourceAddress());

        switch (config.getMode()) {
            case IpSecTransform.MODE_TRANSPORT:
                break;
            case IpSecTransform.MODE_TUNNEL:
                break;
            default:
                throw new IllegalArgumentException(
                        "Invalid IpSecTransform.mode: " + config.getMode());
        }
    }

    private static final String TUNNEL_OP = "STOPSHIP"; // = AppOpsManager.OP_MANAGE_IPSEC_TUNNELS;

    private void enforceTunnelPermissions(String callingPackage) {
        checkNotNull(callingPackage, "Null calling package cannot create IpSec tunnels");
        if (false) { // STOPSHIP if this line is present
            switch (getAppOpsManager().noteOp(
                        TUNNEL_OP,
                        Binder.getCallingUid(), callingPackage)) {
                case AppOpsManager.MODE_DEFAULT:
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.MANAGE_IPSEC_TUNNELS, "IpSecService");
                    break;
                case AppOpsManager.MODE_ALLOWED:
                    return;
                default:
                    throw new SecurityException("Request to ignore AppOps for non-legacy API");
            }
        }
    }

    private void createOrUpdateTransform(
            IpSecConfig c, int resourceId, SpiRecord spiRecord, EncapSocketRecord socketRecord)
            throws RemoteException {

        int encapType = c.getEncapType(), encapLocalPort = 0, encapRemotePort = 0;
        if (encapType != IpSecTransform.ENCAP_NONE) {
            encapLocalPort = socketRecord.getPort();
            encapRemotePort = c.getEncapRemotePort();
        }

        IpSecAlgorithm auth = c.getAuthentication();
        IpSecAlgorithm crypt = c.getEncryption();
        IpSecAlgorithm authCrypt = c.getAuthenticatedEncryption();

        String cryptName;
        if (crypt == null) {
            cryptName = (authCrypt == null) ? IpSecAlgorithm.CRYPT_NULL : "";
        } else {
            cryptName = crypt.getName();
        }

        mSrvConfig
                .getNetdInstance()
                .ipSecAddSecurityAssociation(
                        Binder.getCallingUid(),
                        c.getMode(),
                        c.getSourceAddress(),
                        c.getDestinationAddress(),
                        (c.getNetwork() != null) ? c.getNetwork().netId : 0,
                        spiRecord.getSpi(),
                        c.getMarkValue(),
                        c.getMarkMask(),
                        (auth != null) ? auth.getName() : "",
                        (auth != null) ? auth.getKey() : new byte[] {},
                        (auth != null) ? auth.getTruncationLengthBits() : 0,
                        cryptName,
                        (crypt != null) ? crypt.getKey() : new byte[] {},
                        (crypt != null) ? crypt.getTruncationLengthBits() : 0,
                        (authCrypt != null) ? authCrypt.getName() : "",
                        (authCrypt != null) ? authCrypt.getKey() : new byte[] {},
                        (authCrypt != null) ? authCrypt.getTruncationLengthBits() : 0,
                        encapType,
                        encapLocalPort,
                        encapRemotePort);
    }

    /**
     * Create a IPsec transform, which represents a single security association in the kernel. The
     * transform will be cached by the system server and must be freed when no longer needed. It is
     * possible to free one, deleting the SA from underneath sockets that are using it, which will
     * result in all of those sockets becoming unable to send or receive data.
     */
    @Override
    public synchronized IpSecTransformResponse createTransform(
            IpSecConfig c, IBinder binder, String callingPackage) throws RemoteException {
        checkNotNull(c);
        if (c.getMode() == IpSecTransform.MODE_TUNNEL) {
            enforceTunnelPermissions(callingPackage);
        }
        checkIpSecConfig(c);
        checkNotNull(binder, "Null Binder passed to createTransform");
        final int resourceId = mNextResourceId++;

        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        List<RefcountedResource> dependencies = new ArrayList<>();

        if (!userRecord.mTransformQuotaTracker.isAvailable()) {
            return new IpSecTransformResponse(IpSecManager.Status.RESOURCE_UNAVAILABLE);
        }

        EncapSocketRecord socketRecord = null;
        if (c.getEncapType() != IpSecTransform.ENCAP_NONE) {
            RefcountedResource<EncapSocketRecord> refcountedSocketRecord =
                    userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(
                            c.getEncapSocketResourceId());
            dependencies.add(refcountedSocketRecord);
            socketRecord = refcountedSocketRecord.getResource();
        }

        RefcountedResource<SpiRecord> refcountedSpiRecord =
                userRecord.mSpiRecords.getRefcountedResourceOrThrow(c.getSpiResourceId());
        dependencies.add(refcountedSpiRecord);
        SpiRecord spiRecord = refcountedSpiRecord.getResource();

        createOrUpdateTransform(c, resourceId, spiRecord, socketRecord);

        // SA was created successfully, time to construct a record and lock it away
        userRecord.mTransformRecords.put(
                resourceId,
                new RefcountedResource<TransformRecord>(
                        new TransformRecord(resourceId, c, spiRecord, socketRecord),
                        binder,
                        dependencies.toArray(new RefcountedResource[dependencies.size()])));
        return new IpSecTransformResponse(IpSecManager.Status.OK, resourceId);
    }

    /**
     * Delete a transport mode transform that was previously allocated by + registered with the
     * system server. If this is called on an inactive (or non-existent) transform, it will not
     * return an error. It's safe to de-allocate transforms that may have already been deleted for
     * other reasons.
     */
    @Override
    public synchronized void deleteTransform(int resourceId) throws RemoteException {
        UserRecord userRecord = mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTransformRecords, resourceId);
    }

    /**
     * Apply an active transport mode transform to a socket, which will apply the IPsec security
     * association as a correspondent policy to the provided socket
     */
    @Override
    public synchronized void applyTransportModeTransform(
            ParcelFileDescriptor socket, int direction, int resourceId) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = mUserResourceTracker.getUserRecord(callingUid);
        checkDirection(direction);
        // Get transform record; if no transform is found, will throw IllegalArgumentException
        TransformRecord info = userRecord.mTransformRecords.getResourceOrThrow(resourceId);

        // TODO: make this a function.
        if (info.pid != getCallingPid() || info.uid != callingUid) {
            throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
        }

        // Get config and check that to-be-applied transform has the correct mode
        IpSecConfig c = info.getConfig();
        Preconditions.checkArgument(
                c.getMode() == IpSecTransform.MODE_TRANSPORT,
                "Transform mode was not Transport mode; cannot be applied to a socket");

        mSrvConfig
                .getNetdInstance()
                .ipSecApplyTransportModeTransform(
                        socket.getFileDescriptor(),
                        callingUid,
                        direction,
                        c.getSourceAddress(),
                        c.getDestinationAddress(),
                        info.getSpiRecord().getSpi());
    }

    /**
     * Remove transport mode transforms from a socket, applying the default (empty) policy. This
     * ensures that NO IPsec policy is applied to the socket (would be the equivalent of applying a
     * policy that performs no IPsec). Today the resourceId parameter is passed but not used:
     * reserved for future improved input validation.
     */
    @Override
    public synchronized void removeTransportModeTransforms(ParcelFileDescriptor socket)
            throws RemoteException {
        mSrvConfig
                .getNetdInstance()
                .ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
    }

    /**
     * Apply an active tunnel mode transform to a TunnelInterface, which will apply the IPsec
     * security association as a correspondent policy to the provided interface
     */
    @Override
    public synchronized void applyTunnelModeTransform(
            int tunnelResourceId, int direction,
            int transformResourceId, String callingPackage) throws RemoteException {
        enforceTunnelPermissions(callingPackage);
        checkDirection(direction);

        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = mUserResourceTracker.getUserRecord(callingUid);

        // Get transform record; if no transform is found, will throw IllegalArgumentException
        TransformRecord transformInfo =
                userRecord.mTransformRecords.getResourceOrThrow(transformResourceId);

        // Get tunnelInterface record; if no such interface is found, will throw
        // IllegalArgumentException
        TunnelInterfaceRecord tunnelInterfaceInfo =
                userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);

        // Get config and check that to-be-applied transform has the correct mode
        IpSecConfig c = transformInfo.getConfig();
        Preconditions.checkArgument(
                c.getMode() == IpSecTransform.MODE_TUNNEL,
                "Transform mode was not Tunnel mode; cannot be applied to a tunnel interface");

        EncapSocketRecord socketRecord = null;
        if (c.getEncapType() != IpSecTransform.ENCAP_NONE) {
            socketRecord =
                    userRecord.mEncapSocketRecords.getResourceOrThrow(c.getEncapSocketResourceId());
        }
        SpiRecord spiRecord = userRecord.mSpiRecords.getResourceOrThrow(c.getSpiResourceId());

        int mark =
                (direction == IpSecManager.DIRECTION_OUT)
                        ? tunnelInterfaceInfo.getOkey()
                        : tunnelInterfaceInfo.getIkey();

        try {
            c.setMarkValue(mark);
            c.setMarkMask(0xffffffff);

            if (direction == IpSecManager.DIRECTION_OUT) {
                // Set output mark via underlying network (output only)
                c.setNetwork(tunnelInterfaceInfo.getUnderlyingNetwork());

                // If outbound, also add SPI to the policy.
                for (int selAddrFamily : ADDRESS_FAMILIES) {
                    mSrvConfig
                            .getNetdInstance()
                            .ipSecUpdateSecurityPolicy(
                                    callingUid,
                                    selAddrFamily,
                                    direction,
                                    tunnelInterfaceInfo.getLocalAddress(),
                                    tunnelInterfaceInfo.getRemoteAddress(),
                                    transformInfo.getSpiRecord().getSpi(),
                                    mark,
                                    0xffffffff);
                }
            }

            // Update SA with tunnel mark (ikey or okey based on direction)
            createOrUpdateTransform(c, transformResourceId, spiRecord, socketRecord);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == EINVAL) {
                throw new IllegalArgumentException(e.toString());
            } else {
                throw e;
            }
        }
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("IpSecService dump:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();

        pw.println("mUserResourceTracker:");
        pw.println(mUserResourceTracker);
    }
}

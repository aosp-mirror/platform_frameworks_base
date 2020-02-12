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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.IpSecService.IResource;
import com.android.server.IpSecService.RefcountedResource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Unit tests for {@link IpSecService.RefcountedResource}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IpSecServiceRefcountedResourceTest {
    Context mMockContext;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockIpSecSrvConfig = mock(IpSecService.IpSecServiceConfiguration.class);
        mIpSecService = new IpSecService(
                mMockContext, mock(INetworkManagementService.class), mMockIpSecSrvConfig);
    }

    private void assertResourceState(
            RefcountedResource<IResource> resource,
            int refCount,
            int userReleaseCallCount,
            int releaseReferenceCallCount,
            int invalidateCallCount,
            int freeUnderlyingResourcesCallCount)
            throws RemoteException {
        // Check refcount on RefcountedResource
        assertEquals(refCount, resource.mRefCount);

        // Check call count of RefcountedResource
        verify(resource, times(userReleaseCallCount)).userRelease();
        verify(resource, times(releaseReferenceCallCount)).releaseReference();

        // Check call count of IResource
        verify(resource.getResource(), times(invalidateCallCount)).invalidate();
        verify(resource.getResource(), times(freeUnderlyingResourcesCallCount))
                .freeUnderlyingResources();
    }

    /** Adds mockito instrumentation */
    private RefcountedResource<IResource> getTestRefcountedResource(
            RefcountedResource... children) {
        return getTestRefcountedResource(new Binder(), children);
    }

    /** Adds mockito instrumentation with provided binder */
    private RefcountedResource<IResource> getTestRefcountedResource(
            IBinder binder, RefcountedResource... children) {
        return spy(
                mIpSecService
                .new RefcountedResource<IResource>(mock(IResource.class), binder, children));
    }

    @Test
    public void testConstructor() throws RemoteException {
        IBinder binderMock = mock(IBinder.class);
        RefcountedResource<IResource> resource = getTestRefcountedResource(binderMock);

        // Verify resource's refcount starts at 1 (for user-reference)
        assertResourceState(resource, 1, 0, 0, 0, 0);

        // Verify linking to binder death
        verify(binderMock).linkToDeath(anyObject(), anyInt());
    }

    @Test
    public void testConstructorWithChildren() throws RemoteException {
        IBinder binderMockChild = mock(IBinder.class);
        IBinder binderMockParent = mock(IBinder.class);
        RefcountedResource<IResource> childResource = getTestRefcountedResource(binderMockChild);
        RefcountedResource<IResource> parentResource =
                getTestRefcountedResource(binderMockParent, childResource);

        // Verify parent's refcount starts at 1 (for user-reference)
        assertResourceState(parentResource, 1, 0, 0, 0, 0);

        // Verify child's refcounts were incremented
        assertResourceState(childResource, 2, 0, 0, 0, 0);

        // Verify linking to binder death
        verify(binderMockChild).linkToDeath(anyObject(), anyInt());
        verify(binderMockParent).linkToDeath(anyObject(), anyInt());
    }

    @Test
    public void testFailLinkToDeath() throws RemoteException {
        IBinder binderMock = mock(IBinder.class);
        doThrow(new RemoteException()).when(binderMock).linkToDeath(anyObject(), anyInt());

        try {
            getTestRefcountedResource(binderMock);
            fail("Expected exception to propogate when binder fails to link to death");
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void testCleanupAndRelease() throws RemoteException {
        IBinder binderMock = mock(IBinder.class);
        RefcountedResource<IResource> refcountedResource = getTestRefcountedResource(binderMock);

        // Verify user-initiated cleanup path decrements refcount and calls full cleanup flow
        refcountedResource.userRelease();
        assertResourceState(refcountedResource, -1, 1, 1, 1, 1);

        // Verify user-initated cleanup path unlinks from binder
        verify(binderMock).unlinkToDeath(eq(refcountedResource), eq(0));
        assertNull(refcountedResource.mBinder);
    }

    @Test
    public void testMultipleCallsToCleanupAndRelease() throws RemoteException {
        RefcountedResource<IResource> refcountedResource = getTestRefcountedResource();

        // Verify calling userRelease multiple times does not trigger any other cleanup
        // methods
        refcountedResource.userRelease();
        assertResourceState(refcountedResource, -1, 1, 1, 1, 1);

        refcountedResource.userRelease();
        refcountedResource.userRelease();
        assertResourceState(refcountedResource, -1, 3, 1, 1, 1);
    }

    @Test
    public void testBinderDeathAfterCleanupAndReleaseDoesNothing() throws RemoteException {
        RefcountedResource<IResource> refcountedResource = getTestRefcountedResource();

        refcountedResource.userRelease();
        assertResourceState(refcountedResource, -1, 1, 1, 1, 1);

        // Verify binder death call does not trigger any other cleanup methods if called after
        // userRelease()
        refcountedResource.binderDied();
        assertResourceState(refcountedResource, -1, 2, 1, 1, 1);
    }

    @Test
    public void testBinderDeath() throws RemoteException {
        RefcountedResource<IResource> refcountedResource = getTestRefcountedResource();

        // Verify binder death caused cleanup
        refcountedResource.binderDied();
        verify(refcountedResource, times(1)).binderDied();
        assertResourceState(refcountedResource, -1, 1, 1, 1, 1);
        assertNull(refcountedResource.mBinder);
    }

    @Test
    public void testCleanupParentDecrementsChildRefcount() throws RemoteException {
        RefcountedResource<IResource> childResource = getTestRefcountedResource();
        RefcountedResource<IResource> parentResource = getTestRefcountedResource(childResource);

        parentResource.userRelease();

        // Verify parent gets cleaned up properly, and triggers releaseReference on
        // child
        assertResourceState(childResource, 1, 0, 1, 0, 0);
        assertResourceState(parentResource, -1, 1, 1, 1, 1);
    }

    @Test
    public void testCleanupReferencedChildDoesNotTriggerRelease() throws RemoteException {
        RefcountedResource<IResource> childResource = getTestRefcountedResource();
        RefcountedResource<IResource> parentResource = getTestRefcountedResource(childResource);

        childResource.userRelease();

        // Verify that child does not clean up kernel resources and quota.
        assertResourceState(childResource, 1, 1, 1, 1, 0);
        assertResourceState(parentResource, 1, 0, 0, 0, 0);
    }

    @Test
    public void testTwoParents() throws RemoteException {
        RefcountedResource<IResource> childResource = getTestRefcountedResource();
        RefcountedResource<IResource> parentResource1 = getTestRefcountedResource(childResource);
        RefcountedResource<IResource> parentResource2 = getTestRefcountedResource(childResource);

        // Verify that child does not cleanup kernel resources and quota until all references
        // have been released. Assumption: parents release correctly based on
        // testCleanupParentDecrementsChildRefcount()
        childResource.userRelease();
        assertResourceState(childResource, 2, 1, 1, 1, 0);

        parentResource1.userRelease();
        assertResourceState(childResource, 1, 1, 2, 1, 0);

        parentResource2.userRelease();
        assertResourceState(childResource, -1, 1, 3, 1, 1);
    }

    @Test
    public void testTwoChildren() throws RemoteException {
        RefcountedResource<IResource> childResource1 = getTestRefcountedResource();
        RefcountedResource<IResource> childResource2 = getTestRefcountedResource();
        RefcountedResource<IResource> parentResource =
                getTestRefcountedResource(childResource1, childResource2);

        childResource1.userRelease();
        assertResourceState(childResource1, 1, 1, 1, 1, 0);
        assertResourceState(childResource2, 2, 0, 0, 0, 0);

        parentResource.userRelease();
        assertResourceState(childResource1, -1, 1, 2, 1, 1);
        assertResourceState(childResource2, 1, 0, 1, 0, 0);

        childResource2.userRelease();
        assertResourceState(childResource1, -1, 1, 2, 1, 1);
        assertResourceState(childResource2, -1, 1, 2, 1, 1);
    }

    @Test
    public void testSampleUdpEncapTranform() throws RemoteException {
        RefcountedResource<IResource> spi1 = getTestRefcountedResource();
        RefcountedResource<IResource> spi2 = getTestRefcountedResource();
        RefcountedResource<IResource> udpEncapSocket = getTestRefcountedResource();
        RefcountedResource<IResource> transform =
                getTestRefcountedResource(spi1, spi2, udpEncapSocket);

        // Pretend one SPI goes out of reference (releaseManagedResource -> userRelease)
        spi1.userRelease();

        // User called releaseManagedResource on udpEncap socket
        udpEncapSocket.userRelease();

        // User dies, and binder kills the rest
        spi2.binderDied();
        transform.binderDied();

        // Check resource states
        assertResourceState(spi1, -1, 1, 2, 1, 1);
        assertResourceState(spi2, -1, 1, 2, 1, 1);
        assertResourceState(udpEncapSocket, -1, 1, 2, 1, 1);
        assertResourceState(transform, -1, 1, 1, 1, 1);
    }

    @Test
    public void testSampleDualTransformEncapSocket() throws RemoteException {
        RefcountedResource<IResource> spi1 = getTestRefcountedResource();
        RefcountedResource<IResource> spi2 = getTestRefcountedResource();
        RefcountedResource<IResource> spi3 = getTestRefcountedResource();
        RefcountedResource<IResource> spi4 = getTestRefcountedResource();
        RefcountedResource<IResource> udpEncapSocket = getTestRefcountedResource();
        RefcountedResource<IResource> transform1 =
                getTestRefcountedResource(spi1, spi2, udpEncapSocket);
        RefcountedResource<IResource> transform2 =
                getTestRefcountedResource(spi3, spi4, udpEncapSocket);

        // Pretend one SPIs goes out of reference (releaseManagedResource -> userRelease)
        spi1.userRelease();

        // User called releaseManagedResource on udpEncap socket and spi4
        udpEncapSocket.userRelease();
        spi4.userRelease();

        // User dies, and binder kills the rest
        spi2.binderDied();
        spi3.binderDied();
        transform2.binderDied();
        transform1.binderDied();

        // Check resource states
        assertResourceState(spi1, -1, 1, 2, 1, 1);
        assertResourceState(spi2, -1, 1, 2, 1, 1);
        assertResourceState(spi3, -1, 1, 2, 1, 1);
        assertResourceState(spi4, -1, 1, 2, 1, 1);
        assertResourceState(udpEncapSocket, -1, 1, 3, 1, 1);
        assertResourceState(transform1, -1, 1, 1, 1, 1);
        assertResourceState(transform2, -1, 1, 1, 1, 1);
    }

    @Test
    public void fuzzTest() throws RemoteException {
        List<RefcountedResource<IResource>> resources = new ArrayList<>();

        // Build a tree of resources
        for (int i = 0; i < 100; i++) {
            // Choose a random number of children from the existing list
            int numChildren = ThreadLocalRandom.current().nextInt(0, resources.size() + 1);

            // Build a (random) list of children
            Set<RefcountedResource<IResource>> children = new HashSet<>();
            for (int j = 0; j < numChildren; j++) {
                int childIndex = ThreadLocalRandom.current().nextInt(0, resources.size());
                children.add(resources.get(childIndex));
            }

            RefcountedResource<IResource> newRefcountedResource =
                    getTestRefcountedResource(
                            children.toArray(new RefcountedResource[children.size()]));
            resources.add(newRefcountedResource);
        }

        // Cleanup all resources in a random order
        List<RefcountedResource<IResource>> clonedResources =
                new ArrayList<>(resources); // shallow copy
        while (!clonedResources.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(0, clonedResources.size());
            RefcountedResource<IResource> refcountedResource = clonedResources.get(index);
            refcountedResource.userRelease();
            clonedResources.remove(index);
        }

        // Verify all resources were cleaned up properly
        for (RefcountedResource<IResource> refcountedResource : resources) {
            assertEquals(-1, refcountedResource.mRefCount);
        }
    }
}

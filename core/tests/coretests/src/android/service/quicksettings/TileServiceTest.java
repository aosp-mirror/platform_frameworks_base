/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.quicksettings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class TileServiceTest {

    @Mock
    private IQSService.Stub mIQSService;

    private IBinder mTileToken;
    private TileService mTileService;
    private Tile mTile;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTileToken = new Binder();
        when(mIQSService.asBinder()).thenCallRealMethod();
        when(mIQSService.queryLocalInterface(anyString())).thenReturn(mIQSService);

        mTile = new Tile();

        mTileService = new TileService();
    }

    @Test
    public void testErrorRetrievingTile_nullBinding() throws RemoteException {
        Intent intent = new Intent();
        intent.putExtra(TileService.EXTRA_SERVICE, mIQSService);
        intent.putExtra(TileService.EXTRA_TOKEN, mTileToken);
        when(mIQSService.getTile(mTileToken)).thenThrow(new RemoteException());

        IBinder result = mTileService.onBind(intent);
        assertNull(result);
    }

    @Test
    public void testNullTile_doesntSendStartSuccessful() throws RemoteException {
        Intent intent = new Intent();
        intent.putExtra(TileService.EXTRA_SERVICE, mIQSService);
        intent.putExtra(TileService.EXTRA_TOKEN, mTileToken);
        when(mIQSService.getTile(mTileToken)).thenReturn(null);

        IBinder result = mTileService.onBind(intent);

        assertNotNull(result);
        verify(mIQSService, never()).onStartSuccessful(any());
    }

    @Test
    public void testBindSuccessful() throws RemoteException {
        Intent intent = new Intent();
        intent.putExtra(TileService.EXTRA_SERVICE, mIQSService);
        intent.putExtra(TileService.EXTRA_TOKEN, mTileToken);
        when(mIQSService.getTile(mTileToken)).thenReturn(mTile);

        IBinder result = mTileService.onBind(intent);

        assertNotNull(result);
        verify(mIQSService).onStartSuccessful(mTileToken);

        mTile.updateTile();
        verify(mIQSService).updateQsTile(mTile, mTileToken);
    }

}

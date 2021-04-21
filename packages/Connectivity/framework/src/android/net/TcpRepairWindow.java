/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

/**
 * Corresponds to C's {@code struct tcp_repair_window} from
 * include/uapi/linux/tcp.h
 *
 * @hide
 */
public final class TcpRepairWindow {
    public final int sndWl1;
    public final int sndWnd;
    public final int maxWindow;
    public final int rcvWnd;
    public final int rcvWup;
    public final int rcvWndScale;

    /**
     * Constructs an instance with the given field values.
     */
    public TcpRepairWindow(final int sndWl1, final int sndWnd, final int maxWindow,
            final int rcvWnd, final int rcvWup, final int rcvWndScale) {
        this.sndWl1 = sndWl1;
        this.sndWnd = sndWnd;
        this.maxWindow = maxWindow;
        this.rcvWnd = rcvWnd;
        this.rcvWup = rcvWup;
        this.rcvWndScale = rcvWndScale;
    }
}

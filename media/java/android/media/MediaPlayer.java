/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media;

import static android.Manifest.permission.BIND_IMS_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.AttributionSource.ScopedParcelState;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.SubtitleController.Anchor;
import android.media.SubtitleTrack.RenderingWidget;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.VideoView;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import libcore.io.IoBridge;
import libcore.io.Streams;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.Executor;

/**
 * MediaPlayer class can be used to control playback of audio/video files and streams.
 *
 * <p>MediaPlayer is not thread-safe. Creation of and all access to player instances
 * should be on the same thread. If registering <a href="#Callbacks">callbacks</a>,
 * the thread must have a Looper.
 *
 * <p>Topics covered here are:
 * <ol>
 * <li><a href="#StateDiagram">State Diagram</a>
 * <li><a href="#Valid_and_Invalid_States">Valid and Invalid States</a>
 * <li><a href="#Permissions">Permissions</a>
 * <li><a href="#Callbacks">Register informational and error callbacks</a>
 * </ol>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use MediaPlayer, read the
 * <a href="{@docRoot}guide/topics/media/mediaplayer.html">Media Playback</a> developer guide.</p>
 * </div>
 *
 * <a name="StateDiagram"></a>
 * <h3>State Diagram</h3>
 *
 * <p>Playback control of audio/video files and streams is managed as a state
 * machine. The following diagram shows the life cycle and the states of a
 * MediaPlayer object driven by the supported playback control operations.
 * The ovals represent the states a MediaPlayer object may reside
 * in. The arcs represent the playback control operations that drive the object
 * state transition. There are two types of arcs. The arcs with a single arrow
 * head represent synchronous method calls, while those with
 * a double arrow head represent asynchronous method calls.</p>
 *
 * <p><img src="../../../images/mediaplayer_state_diagram.gif"
 *         alt="MediaPlayer State diagram"
 *         border="0" /></p>
 *
 * <p>From this state diagram, one can see that a MediaPlayer object has the
 *    following states:</p>
 * <ul>
 *     <li>When a MediaPlayer object is just created using <code>new</code> or
 *         after {@link #reset()} is called, it is in the <em>Idle</em> state; and after
 *         {@link #release()} is called, it is in the <em>End</em> state. Between these
 *         two states is the life cycle of the MediaPlayer object.
 *         <ul>
 *         <li>There is a subtle but important difference between a newly constructed
 *         MediaPlayer object and the MediaPlayer object after {@link #reset()}
 *         is called. It is a programming error to invoke methods such
 *         as {@link #getCurrentPosition()},
 *         {@link #getDuration()}, {@link #getVideoHeight()},
 *         {@link #getVideoWidth()}, {@link #setAudioAttributes(AudioAttributes)},
 *         {@link #setLooping(boolean)},
 *         {@link #setVolume(float, float)}, {@link #pause()}, {@link #start()},
 *         {@link #stop()}, {@link #seekTo(long, int)}, {@link #prepare()} or
 *         {@link #prepareAsync()} in the <em>Idle</em> state for both cases. If any of these
 *         methods is called right after a MediaPlayer object is constructed,
 *         the user supplied callback method OnErrorListener.onError() won't be
 *         called by the internal player engine and the object state remains
 *         unchanged; but if these methods are called right after {@link #reset()},
 *         the user supplied callback method OnErrorListener.onError() will be
 *         invoked by the internal player engine and the object will be
 *         transfered to the <em>Error</em> state. </li>
 *         <li>You must call {@link #release()} once you have finished using an instance to release
 *         acquired resources, such as memory and codecs. Once you have called {@link #release}, you
 *         must no longer interact with the released instance.
 *         <li>MediaPlayer objects created using <code>new</code> is in the
 *         <em>Idle</em> state, while those created with one
 *         of the overloaded convenient <code>create</code> methods are <em>NOT</em>
 *         in the <em>Idle</em> state. In fact, the objects are in the <em>Prepared</em>
 *         state if the creation using <code>create</code> method is successful.
 *         </li>
 *         </ul>
 *         </li>
 *     <li>In general, some playback control operation may fail due to various
 *         reasons, such as unsupported audio/video format, poorly interleaved
 *         audio/video, resolution too high, streaming timeout, and the like.
 *         Thus, error reporting and recovery is an important concern under
 *         these circumstances. Sometimes, due to programming errors, invoking a playback
 *         control operation in an invalid state may also occur. Under all these
 *         error conditions, the internal player engine invokes a user supplied
 *         OnErrorListener.onError() method if an OnErrorListener has been
 *         registered beforehand via
 *         {@link #setOnErrorListener(android.media.MediaPlayer.OnErrorListener)}.
 *         <ul>
 *         <li>It is important to note that once an error occurs, the
 *         MediaPlayer object enters the <em>Error</em> state (except as noted
 *         above), even if an error listener has not been registered by the application.</li>
 *         <li>In order to reuse a MediaPlayer object that is in the <em>
 *         Error</em> state and recover from the error,
 *         {@link #reset()} can be called to restore the object to its <em>Idle</em>
 *         state.</li>
 *         <li>It is good programming practice to have your application
 *         register a OnErrorListener to look out for error notifications from
 *         the internal player engine.</li>
 *         <li>IllegalStateException is
 *         thrown to prevent programming errors such as calling {@link #prepare()},
 *         {@link #prepareAsync()}, or one of the overloaded <code>setDataSource
 *         </code> methods in an invalid state. </li>
 *         </ul>
 *         </li>
 *     <li>Calling
 *         {@link #setDataSource(FileDescriptor)}, or
 *         {@link #setDataSource(String)}, or
 *         {@link #setDataSource(Context, Uri)}, or
 *         {@link #setDataSource(FileDescriptor, long, long)}, or
 *         {@link #setDataSource(MediaDataSource)} transfers a
 *         MediaPlayer object in the <em>Idle</em> state to the
 *         <em>Initialized</em> state.
 *         <ul>
 *         <li>An IllegalStateException is thrown if
 *         setDataSource() is called in any other state.</li>
 *         <li>It is good programming
 *         practice to always look out for <code>IllegalArgumentException</code>
 *         and <code>IOException</code> that may be thrown from the overloaded
 *         <code>setDataSource</code> methods.</li>
 *         </ul>
 *         </li>
 *     <li>A MediaPlayer object must first enter the <em>Prepared</em> state
 *         before playback can be started.
 *         <ul>
 *         <li>There are two ways (synchronous vs.
 *         asynchronous) that the <em>Prepared</em> state can be reached:
 *         either a call to {@link #prepare()} (synchronous) which
 *         transfers the object to the <em>Prepared</em> state once the method call
 *         returns, or a call to {@link #prepareAsync()} (asynchronous) which
 *         first transfers the object to the <em>Preparing</em> state after the
 *         call returns (which occurs almost right away) while the internal
 *         player engine continues working on the rest of preparation work
 *         until the preparation work completes. When the preparation completes or when {@link #prepare()} call returns,
 *         the internal player engine then calls a user supplied callback method,
 *         onPrepared() of the OnPreparedListener interface, if an
 *         OnPreparedListener is registered beforehand via {@link
 *         #setOnPreparedListener(android.media.MediaPlayer.OnPreparedListener)}.</li>
 *         <li>It is important to note that
 *         the <em>Preparing</em> state is a transient state, and the behavior
 *         of calling any method with side effect while a MediaPlayer object is
 *         in the <em>Preparing</em> state is undefined.</li>
 *         <li>An IllegalStateException is
 *         thrown if {@link #prepare()} or {@link #prepareAsync()} is called in
 *         any other state.</li>
 *         <li>While in the <em>Prepared</em> state, properties
 *         such as audio/sound volume, screenOnWhilePlaying, looping can be
 *         adjusted by invoking the corresponding set methods.</li>
 *         </ul>
 *         </li>
 *     <li>To start the playback, {@link #start()} must be called. After
 *         {@link #start()} returns successfully, the MediaPlayer object is in the
 *         <em>Started</em> state. {@link #isPlaying()} can be called to test
 *         whether the MediaPlayer object is in the <em>Started</em> state.
 *         <ul>
 *         <li>While in the <em>Started</em> state, the internal player engine calls
 *         a user supplied OnBufferingUpdateListener.onBufferingUpdate() callback
 *         method if a OnBufferingUpdateListener has been registered beforehand
 *         via {@link #setOnBufferingUpdateListener(OnBufferingUpdateListener)}.
 *         This callback allows applications to keep track of the buffering status
 *         while streaming audio/video.</li>
 *         <li>Calling {@link #start()} has no effect
 *         on a MediaPlayer object that is already in the <em>Started</em> state.</li>
 *         </ul>
 *         </li>
 *     <li>Playback can be paused and stopped, and the current playback position
 *         can be adjusted. Playback can be paused via {@link #pause()}. When the call to
 *         {@link #pause()} returns, the MediaPlayer object enters the
 *         <em>Paused</em> state. Note that the transition from the <em>Started</em>
 *         state to the <em>Paused</em> state and vice versa happens
 *         asynchronously in the player engine. It may take some time before
 *         the state is updated in calls to {@link #isPlaying()}, and it can be
 *         a number of seconds in the case of streamed content.
 *         <ul>
 *         <li>Calling {@link #start()} to resume playback for a paused
 *         MediaPlayer object, and the resumed playback
 *         position is the same as where it was paused. When the call to
 *         {@link #start()} returns, the paused MediaPlayer object goes back to
 *         the <em>Started</em> state.</li>
 *         <li>Calling {@link #pause()} has no effect on
 *         a MediaPlayer object that is already in the <em>Paused</em> state.</li>
 *         </ul>
 *         </li>
 *     <li>Calling  {@link #stop()} stops playback and causes a
 *         MediaPlayer in the <em>Started</em>, <em>Paused</em>, <em>Prepared
 *         </em> or <em>PlaybackCompleted</em> state to enter the
 *         <em>Stopped</em> state.
 *         <ul>
 *         <li>Once in the <em>Stopped</em> state, playback cannot be started
 *         until {@link #prepare()} or {@link #prepareAsync()} are called to set
 *         the MediaPlayer object to the <em>Prepared</em> state again.</li>
 *         <li>Calling {@link #stop()} has no effect on a MediaPlayer
 *         object that is already in the <em>Stopped</em> state.</li>
 *         </ul>
 *         </li>
 *     <li>The playback position can be adjusted with a call to
 *         {@link #seekTo(long, int)}.
 *         <ul>
 *         <li>Although the asynchronuous {@link #seekTo(long, int)}
 *         call returns right away, the actual seek operation may take a while to
 *         finish, especially for audio/video being streamed. When the actual
 *         seek operation completes, the internal player engine calls a user
 *         supplied OnSeekComplete.onSeekComplete() if an OnSeekCompleteListener
 *         has been registered beforehand via
 *         {@link #setOnSeekCompleteListener(OnSeekCompleteListener)}.</li>
 *         <li>Please
 *         note that {@link #seekTo(long, int)} can also be called in the other states,
 *         such as <em>Prepared</em>, <em>Paused</em> and <em>PlaybackCompleted
 *         </em> state. When {@link #seekTo(long, int)} is called in those states,
 *         one video frame will be displayed if the stream has video and the requested
 *         position is valid.
 *         </li>
 *         <li>Furthermore, the actual current playback position
 *         can be retrieved with a call to {@link #getCurrentPosition()}, which
 *         is helpful for applications such as a Music player that need to keep
 *         track of the playback progress.</li>
 *         </ul>
 *         </li>
 *     <li>When the playback reaches the end of stream, the playback completes.
 *         <ul>
 *         <li>If the looping mode was being set to <var>true</var> with
 *         {@link #setLooping(boolean)}, the MediaPlayer object shall remain in
 *         the <em>Started</em> state.</li>
 *         <li>If the looping mode was set to <var>false
 *         </var>, the player engine calls a user supplied callback method,
 *         OnCompletion.onCompletion(), if a OnCompletionListener is registered
 *         beforehand via {@link #setOnCompletionListener(OnCompletionListener)}.
 *         The invoke of the callback signals that the object is now in the <em>
 *         PlaybackCompleted</em> state.</li>
 *         <li>While in the <em>PlaybackCompleted</em>
 *         state, calling {@link #start()} can restart the playback from the
 *         beginning of the audio/video source.</li>
 * </ul>
 *
 *
 * <a name="Valid_and_Invalid_States"></a>
 * <h3>Valid and invalid states</h3>
 *
 * <table border="0" cellspacing="0" cellpadding="0">
 * <tr><td>Method Name </p></td>
 *     <td>Valid States </p></td>
 *     <td>Invalid States </p></td>
 *     <td>Comments </p></td></tr>
 * <tr><td>attachAuxEffect </p></td>
 *     <td>{Initialized, Prepared, Started, Paused, Stopped, PlaybackCompleted} </p></td>
 *     <td>{Idle, Error} </p></td>
 *     <td>This method must be called after setDataSource.
 *     Calling it does not change the object state. </p></td></tr>
 * <tr><td>getAudioSessionId </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>getCurrentPosition </p></td>
 *     <td>{Idle, Initialized, Prepared, Started, Paused, Stopped,
 *         PlaybackCompleted} </p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change the
 *         state. Calling this method in an invalid state transfers the object
 *         to the <em>Error</em> state. </p></td></tr>
 * <tr><td>getDuration </p></td>
 *     <td>{Prepared, Started, Paused, Stopped, PlaybackCompleted} </p></td>
 *     <td>{Idle, Initialized, Error} </p></td>
 *     <td>Successful invoke of this method in a valid state does not change the
 *         state. Calling this method in an invalid state transfers the object
 *         to the <em>Error</em> state. </p></td></tr>
 * <tr><td>getVideoHeight </p></td>
 *     <td>{Idle, Initialized, Prepared, Started, Paused, Stopped,
 *         PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change the
 *         state. Calling this method in an invalid state transfers the object
 *         to the <em>Error</em> state.  </p></td></tr>
 * <tr><td>getVideoWidth </p></td>
 *     <td>{Idle, Initialized, Prepared, Started, Paused, Stopped,
 *         PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change
 *         the state. Calling this method in an invalid state transfers the
 *         object to the <em>Error</em> state. </p></td></tr>
 * <tr><td>isPlaying </p></td>
 *     <td>{Idle, Initialized, Prepared, Started, Paused, Stopped,
 *          PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change
 *         the state. Calling this method in an invalid state transfers the
 *         object to the <em>Error</em> state. </p></td></tr>
 * <tr><td>pause </p></td>
 *     <td>{Started, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Prepared, Stopped, Error}</p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Paused</em> state. Calling this method in an
 *         invalid state transfers the object to the <em>Error</em> state.</p></td></tr>
 * <tr><td>prepare </p></td>
 *     <td>{Initialized, Stopped} </p></td>
 *     <td>{Idle, Prepared, Started, Paused, PlaybackCompleted, Error} </p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Prepared</em> state. Calling this method in an
 *         invalid state throws an IllegalStateException.</p></td></tr>
 * <tr><td>prepareAsync </p></td>
 *     <td>{Initialized, Stopped} </p></td>
 *     <td>{Idle, Prepared, Started, Paused, PlaybackCompleted, Error} </p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Preparing</em> state. Calling this method in an
 *         invalid state throws an IllegalStateException.</p></td></tr>
 * <tr><td>release </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>After {@link #release()}, you must not interact with the object. </p></td></tr>
 * <tr><td>reset </p></td>
 *     <td>{Idle, Initialized, Prepared, Started, Paused, Stopped,
 *         PlaybackCompleted, Error}</p></td>
 *     <td>{}</p></td>
 *     <td>After {@link #reset()}, the object is like being just created.</p></td></tr>
 * <tr><td>seekTo </p></td>
 *     <td>{Prepared, Started, Paused, PlaybackCompleted} </p></td>
 *     <td>{Idle, Initialized, Stopped, Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change
 *         the state. Calling this method in an invalid state transfers the
 *         object to the <em>Error</em> state. </p></td></tr>
 * <tr><td>setAudioAttributes </p></td>
 *     <td>{Idle, Initialized, Stopped, Prepared, Started, Paused,
 *          PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method does not change the state. In order for the
 *         target audio attributes type to become effective, this method must be called before
 *         prepare() or prepareAsync().</p></td></tr>
 * <tr><td>setAudioSessionId </p></td>
 *     <td>{Idle} </p></td>
 *     <td>{Initialized, Prepared, Started, Paused, Stopped, PlaybackCompleted,
 *          Error} </p></td>
 *     <td>This method must be called in idle state as the audio session ID must be known before
 *         calling setDataSource. Calling it does not change the object state. </p></td></tr>
 * <tr><td>setAudioStreamType (deprecated)</p></td>
 *     <td>{Idle, Initialized, Stopped, Prepared, Started, Paused,
 *          PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method does not change the state. In order for the
 *         target audio stream type to become effective, this method must be called before
 *         prepare() or prepareAsync().</p></td></tr>
 * <tr><td>setAuxEffectSendLevel </p></td>
 *     <td>any</p></td>
 *     <td>{} </p></td>
 *     <td>Calling this method does not change the object state. </p></td></tr>
 * <tr><td>setDataSource </p></td>
 *     <td>{Idle} </p></td>
 *     <td>{Initialized, Prepared, Started, Paused, Stopped, PlaybackCompleted,
 *          Error} </p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Initialized</em> state. Calling this method in an
 *         invalid state throws an IllegalStateException.</p></td></tr>
 * <tr><td>setDisplay </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setSurface </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setVideoScalingMode </p></td>
 *     <td>{Initialized, Prepared, Started, Paused, Stopped, PlaybackCompleted} </p></td>
 *     <td>{Idle, Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.</p></td></tr>
 * <tr><td>setLooping </p></td>
 *     <td>{Idle, Initialized, Stopped, Prepared, Started, Paused,
 *         PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method in a valid state does not change
 *         the state. Calling this method in an
 *         invalid state transfers the object to the <em>Error</em> state.</p></td></tr>
 * <tr><td>isLooping </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setOnBufferingUpdateListener </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setOnCompletionListener </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setOnErrorListener </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setOnPreparedListener </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setOnSeekCompleteListener </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state. </p></td></tr>
 * <tr><td>setPlaybackParams</p></td>
 *     <td>{Initialized, Prepared, Started, Paused, PlaybackCompleted, Error}</p></td>
 *     <td>{Idle, Stopped} </p></td>
 *     <td>This method will change state in some cases, depending on when it's called.
 *         </p></td></tr>
 * <tr><td>setScreenOnWhilePlaying</></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state.  </p></td></tr>
 * <tr><td>setVolume </p></td>
 *     <td>{Idle, Initialized, Stopped, Prepared, Started, Paused,
 *          PlaybackCompleted}</p></td>
 *     <td>{Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.
 * <tr><td>setWakeMode </p></td>
 *     <td>any </p></td>
 *     <td>{} </p></td>
 *     <td>This method can be called in any state and calling it does not change
 *         the object state.</p></td></tr>
 * <tr><td>start </p></td>
 *     <td>{Prepared, Started, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Stopped, Error}</p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Started</em> state. Calling this method in an
 *         invalid state transfers the object to the <em>Error</em> state.</p></td></tr>
 * <tr><td>stop </p></td>
 *     <td>{Prepared, Started, Stopped, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Error}</p></td>
 *     <td>Successful invoke of this method in a valid state transfers the
 *         object to the <em>Stopped</em> state. Calling this method in an
 *         invalid state transfers the object to the <em>Error</em> state.</p></td></tr>
 * <tr><td>getTrackInfo </p></td>
 *     <td>{Prepared, Started, Stopped, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.</p></td></tr>
 * <tr><td>addTimedTextSource </p></td>
 *     <td>{Prepared, Started, Stopped, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.</p></td></tr>
 * <tr><td>selectTrack </p></td>
 *     <td>{Prepared, Started, Stopped, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.</p></td></tr>
 * <tr><td>deselectTrack </p></td>
 *     <td>{Prepared, Started, Stopped, Paused, PlaybackCompleted}</p></td>
 *     <td>{Idle, Initialized, Error}</p></td>
 *     <td>Successful invoke of this method does not change the state.</p></td></tr>
 *
 * </table>
 *
 * <a name="Permissions"></a>
 * <h3>Permissions</h3>
 * <p>One may need to declare a corresponding WAKE_LOCK permission {@link
 * android.R.styleable#AndroidManifestUsesPermission &lt;uses-permission&gt;}
 * element.
 *
 * <p>This class requires the {@link android.Manifest.permission#INTERNET} permission
 * when used with network-based content.
 *
 * <a name="Callbacks"></a>
 * <h3>Callbacks</h3>
 * <p>Applications may want to register for informational and error
 * events in order to be informed of some internal state update and
 * possible runtime errors during playback or streaming. Registration for
 * these events is done by properly setting the appropriate listeners (via calls
 * to
 * {@link #setOnPreparedListener(OnPreparedListener) setOnPreparedListener},
 * {@link #setOnVideoSizeChangedListener(OnVideoSizeChangedListener) setOnVideoSizeChangedListener},
 * {@link #setOnSeekCompleteListener(OnSeekCompleteListener) setOnSeekCompleteListener},
 * {@link #setOnCompletionListener(OnCompletionListener) setOnCompletionListener},
 * {@link #setOnBufferingUpdateListener(OnBufferingUpdateListener) setOnBufferingUpdateListener},
 * {@link #setOnInfoListener(OnInfoListener) setOnInfoListener},
 * {@link #setOnErrorListener(OnErrorListener) setOnErrorListener}, etc).
 * In order to receive the respective callback
 * associated with these listeners, applications are required to create
 * MediaPlayer objects on a thread with its own Looper running (main UI
 * thread by default has a Looper running).
 *
 */
public class MediaPlayer extends PlayerBase
                         implements SubtitleController.Listener
                                  , VolumeAutomation
                                  , AudioRouting
{
    /**
       Constant to retrieve only the new metadata since the last
       call.
       // FIXME: unhide.
       // FIXME: add link to getMetadata(boolean, boolean)
       {@hide}
     */
    public static final boolean METADATA_UPDATE_ONLY = true;

    /**
       Constant to retrieve all the metadata.
       // FIXME: unhide.
       // FIXME: add link to getMetadata(boolean, boolean)
       {@hide}
     */
    @UnsupportedAppUsage
    public static final boolean METADATA_ALL = false;

    /**
       Constant to enable the metadata filter during retrieval.
       // FIXME: unhide.
       // FIXME: add link to getMetadata(boolean, boolean)
       {@hide}
     */
    public static final boolean APPLY_METADATA_FILTER = true;

    /**
       Constant to disable the metadata filter during retrieval.
       // FIXME: unhide.
       // FIXME: add link to getMetadata(boolean, boolean)
       {@hide}
     */
    @UnsupportedAppUsage
    public static final boolean BYPASS_METADATA_FILTER = false;

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private final static String TAG = "MediaPlayer";
    // Name of the remote interface for the media player. Must be kept
    // in sync with the 2nd parameter of the IMPLEMENT_META_INTERFACE
    // macro invocation in IMediaPlayer.cpp
    private final static String IMEDIA_PLAYER = "android.media.IMediaPlayer";

    private long mNativeContext; // accessed by native methods
    private long mNativeSurfaceTexture;  // accessed by native methods
    private int mListenerContext; // accessed by native methods
    private SurfaceHolder mSurfaceHolder;
    @UnsupportedAppUsage
    private EventHandler mEventHandler;
    private PowerManager.WakeLock mWakeLock = null;
    private boolean mScreenOnWhilePlaying;
    private boolean mStayAwake;
    private int mStreamType = AudioManager.USE_DEFAULT_STREAM_TYPE;

    // Modular DRM
    private UUID mDrmUUID;
    private final Object mDrmLock = new Object();
    private DrmInfo mDrmInfo;
    private MediaDrm mDrmObj;
    private byte[] mDrmSessionId;
    private boolean mDrmInfoResolved;
    private boolean mActiveDrmScheme;
    private boolean mDrmConfigAllowed;
    private boolean mDrmProvisioningInProgress;
    private boolean mPrepareDrmInProgress;
    private ProvisioningThread mDrmProvisioningThread;

    /**
     * Default constructor.
     *
     * <p>Consider using one of the create() methods for synchronously instantiating a MediaPlayer
     * from a Uri or resource.
     *
     * <p>You must call {@link #release()} when you are finished using the instantiated instance.
     * Doing so frees any resources you have previously acquired.
     */
    public MediaPlayer() {
        this(AudioSystem.AUDIO_SESSION_ALLOCATE);
    }

    private MediaPlayer(int sessionId) {
        super(new AudioAttributes.Builder().build(),
                AudioPlaybackConfiguration.PLAYER_TYPE_JAM_MEDIAPLAYER);

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        mTimeProvider = new TimeProvider(this);
        mOpenSubtitleSources = new Vector<InputStream>();

        AttributionSource attributionSource = AttributionSource.myAttributionSource();
        // set the package name to empty if it was null
        if (attributionSource.getPackageName() == null) {
            attributionSource = attributionSource.withPackageName("");
        }

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        try (ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState()) {
            native_setup(new WeakReference<MediaPlayer>(this), attributionSourceState.getParcel());
        }

        baseRegisterPlayer(sessionId);
    }

    /*
     * Update the MediaPlayer SurfaceTexture.
     * Call after setting a new display surface.
     */
    private native void _setVideoSurface(Surface surface);

    /* Do not change these values (starting with INVOKE_ID) without updating
     * their counterparts in include/media/mediaplayer.h!
     */
    private static final int INVOKE_ID_GET_TRACK_INFO = 1;
    private static final int INVOKE_ID_ADD_EXTERNAL_SOURCE = 2;
    private static final int INVOKE_ID_ADD_EXTERNAL_SOURCE_FD = 3;
    private static final int INVOKE_ID_SELECT_TRACK = 4;
    private static final int INVOKE_ID_DESELECT_TRACK = 5;
    private static final int INVOKE_ID_SET_VIDEO_SCALE_MODE = 6;
    private static final int INVOKE_ID_GET_SELECTED_TRACK = 7;

    /**
     * Create a request parcel which can be routed to the native media
     * player using {@link #invoke(Parcel, Parcel)}. The Parcel
     * returned has the proper InterfaceToken set. The caller should
     * not overwrite that token, i.e it can only append data to the
     * Parcel.
     *
     * @return A parcel suitable to hold a request for the native
     * player.
     * {@hide}
     */
    @UnsupportedAppUsage
    public Parcel newRequest() {
        Parcel parcel = Parcel.obtain();
        parcel.writeInterfaceToken(IMEDIA_PLAYER);
        return parcel;
    }

    /**
     * Invoke a generic method on the native player using opaque
     * parcels for the request and reply. Both payloads' format is a
     * convention between the java caller and the native player.
     * Must be called after setDataSource to make sure a native player
     * exists. On failure, a RuntimeException is thrown.
     *
     * @param request Parcel with the data for the extension. The
     * caller must use {@link #newRequest()} to get one.
     *
     * @param reply Output parcel with the data returned by the
     * native player.
     * {@hide}
     */
    @UnsupportedAppUsage
    public void invoke(Parcel request, Parcel reply) {
        int retcode = native_invoke(request, reply);
        reply.setDataPosition(0);
        if (retcode != 0) {
            throw new RuntimeException("failure code: " + retcode);
        }
    }

    /**
     * Sets the {@link SurfaceHolder} to use for displaying the video
     * portion of the media.
     *
     * Either a surface holder or surface must be set if a display or video sink
     * is needed.  Not calling this method or {@link #setSurface(Surface)}
     * when playing back a video will result in only the audio track being played.
     * A null surface holder or surface will result in only the audio track being
     * played.
     *
     * @param sh the SurfaceHolder to use for video display
     * @throws IllegalStateException if the internal player engine has not been
     * initialized or has been released.
     */
    public void setDisplay(SurfaceHolder sh) {
        mSurfaceHolder = sh;
        Surface surface;
        if (sh != null) {
            surface = sh.getSurface();
        } else {
            surface = null;
        }
        _setVideoSurface(surface);
        updateSurfaceScreenOn();
    }

    /**
     * Sets the {@link Surface} to be used as the sink for the video portion of
     * the media. This is similar to {@link #setDisplay(SurfaceHolder)}, but
     * does not support {@link #setScreenOnWhilePlaying(boolean)}.  Setting a
     * Surface will un-set any Surface or SurfaceHolder that was previously set.
     * A null surface will result in only the audio track being played.
     *
     * If the Surface sends frames to a {@link SurfaceTexture}, the timestamps
     * returned from {@link SurfaceTexture#getTimestamp()} will have an
     * unspecified zero point.  These timestamps cannot be directly compared
     * between different media sources, different instances of the same media
     * source, or multiple runs of the same program.  The timestamp is normally
     * monotonically increasing and is unaffected by time-of-day adjustments,
     * but it is reset when the position is set.
     *
     * @param surface The {@link Surface} to be used for the video portion of
     * the media.
     * @throws IllegalStateException if the internal player engine has not been
     * initialized or has been released.
     */
    public void setSurface(Surface surface) {
        if (mScreenOnWhilePlaying && surface != null) {
            Log.w(TAG, "setScreenOnWhilePlaying(true) is ineffective for Surface");
        }
        mSurfaceHolder = null;
        _setVideoSurface(surface);
        updateSurfaceScreenOn();
    }

    /* Do not change these video scaling mode values below without updating
     * their counterparts in system/window.h! Please do not forget to update
     * {@link #isVideoScalingModeSupported} when new video scaling modes
     * are added.
     */
    /**
     * Specifies a video scaling mode. The content is stretched to the
     * surface rendering area. When the surface has the same aspect ratio
     * as the content, the aspect ratio of the content is maintained;
     * otherwise, the aspect ratio of the content is not maintained when video
     * is being rendered. Unlike {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING},
     * there is no content cropping with this video scaling mode.
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1;

    /**
     * Specifies a video scaling mode. The content is scaled, maintaining
     * its aspect ratio. The whole surface area is always used. When the
     * aspect ratio of the content is the same as the surface, no content
     * is cropped; otherwise, content is cropped to fit the surface.
     */
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;
    /**
     * Sets video scaling mode. To make the target video scaling mode
     * effective during playback, this method must be called after
     * data source is set. If not called, the default video
     * scaling mode is {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT}.
     *
     * <p> The supported video scaling modes are:
     * <ul>
     * <li> {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT}
     * <li> {@link #VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING}
     * </ul>
     *
     * @param mode target video scaling mode. Must be one of the supported
     * video scaling modes; otherwise, IllegalArgumentException will be thrown.
     *
     * @see MediaPlayer#VIDEO_SCALING_MODE_SCALE_TO_FIT
     * @see MediaPlayer#VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
     */
    public void setVideoScalingMode(int mode) {
        if (!isVideoScalingModeSupported(mode)) {
            final String msg = "Scaling mode " + mode + " is not supported";
            throw new IllegalArgumentException(msg);
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(IMEDIA_PLAYER);
            request.writeInt(INVOKE_ID_SET_VIDEO_SCALE_MODE);
            request.writeInt(mode);
            invoke(request, reply);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    /**
     * Convenience method to create a MediaPlayer for a given Uri.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     *
     * <p>You must call {@link #release()} when you are finished using the created instance. Doing
     * so frees any resources you have previously acquired.
     *
     * <p>Note that since {@link #prepare()} is called automatically in this method,
     * you cannot change the audio
     * session ID (see {@link #setAudioSessionId(int)}) or audio attributes
     * (see {@link #setAudioAttributes(AudioAttributes)} of the new MediaPlayer.</p>
     *
     * @param context the Context to use
     * @param uri the Uri from which to get the datasource
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, Uri uri) {
        return create (context, uri, null);
    }

    /**
     * Convenience method to create a MediaPlayer for a given Uri.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     *
     * <p>You must call {@link #release()} when you are finished using the created instance. Doing
     * so frees any resources you have previously acquired.
     *
     * <p>Note that since {@link #prepare()} is called automatically in this method,
     * you cannot change the audio
     * session ID (see {@link #setAudioSessionId(int)}) or audio attributes
     * (see {@link #setAudioAttributes(AudioAttributes)} of the new MediaPlayer.</p>
     *
     * @param context the Context to use
     * @param uri the Uri from which to get the datasource
     * @param holder the SurfaceHolder to use for displaying the video
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, Uri uri, SurfaceHolder holder) {
        int s = AudioSystem.newAudioSessionId();
        return create(context, uri, holder, null, s > 0 ? s : 0);
    }

    /**
     * Same factory method as {@link #create(Context, Uri, SurfaceHolder)} but that lets you specify
     * the audio attributes and session ID to be used by the new MediaPlayer instance.
     * @param context the Context to use
     * @param uri the Uri from which to get the datasource
     * @param holder the SurfaceHolder to use for displaying the video, may be null.
     * @param audioAttributes the {@link AudioAttributes} to be used by the media player.
     * @param audioSessionId the audio session ID to be used by the media player,
     *     see {@link AudioManager#generateAudioSessionId()} to obtain a new session.
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, Uri uri, SurfaceHolder holder,
            AudioAttributes audioAttributes, int audioSessionId) {

        try {
            MediaPlayer mp = new MediaPlayer(audioSessionId);
            final AudioAttributes aa = audioAttributes != null ? audioAttributes :
                new AudioAttributes.Builder().build();
            mp.setAudioAttributes(aa);
            mp.native_setAudioSessionId(audioSessionId);
            mp.setDataSource(context, uri);
            if (holder != null) {
                mp.setDisplay(holder);
            }
            mp.prepare();
            return mp;
        } catch (IOException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (SecurityException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        }

        return null;
    }

    // Note no convenience method to create a MediaPlayer with SurfaceTexture sink.

    /**
     * Convenience method to create a MediaPlayer for a given resource id.
     * On success, {@link #prepare()} will already have been called and must not be called again.
     *
     * <p>You must call {@link #release()} when you are finished using the created instance. Doing
     * so frees any resources you have previously acquired.
     *
     * <p>Note that since {@link #prepare()} is called automatically in this method,
     * you cannot change the audio
     * session ID (see {@link #setAudioSessionId(int)}) or audio attributes
     * (see {@link #setAudioAttributes(AudioAttributes)} of the new MediaPlayer.</p>
     *
     * @param context the Context to use
     * @param resid the raw resource id (<var>R.raw.&lt;something></var>) for
     *              the resource to use as the datasource
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, int resid) {
        int s = AudioSystem.newAudioSessionId();
        return create(context, resid, null, s > 0 ? s : 0);
    }

    /**
     * Same factory method as {@link #create(Context, int)} but that lets you specify the audio
     * attributes and session ID to be used by the new MediaPlayer instance.
     * @param context the Context to use
     * @param resid the raw resource id (<var>R.raw.&lt;something></var>) for
     *              the resource to use as the datasource
     * @param audioAttributes the {@link AudioAttributes} to be used by the media player.
     * @param audioSessionId the audio session ID to be used by the media player,
     *     see {@link AudioManager#generateAudioSessionId()} to obtain a new session.
     * @return a MediaPlayer object, or null if creation failed
     */
    public static MediaPlayer create(Context context, int resid,
            AudioAttributes audioAttributes, int audioSessionId) {
        try {
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(resid);
            if (afd == null) return null;

            MediaPlayer mp = new MediaPlayer(audioSessionId);

            final AudioAttributes aa = audioAttributes != null ? audioAttributes :
                new AudioAttributes.Builder().build();
            mp.setAudioAttributes(aa);
            mp.native_setAudioSessionId(audioSessionId);

            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            return mp;
        } catch (IOException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "create failed:", ex);
           // fall through
        } catch (SecurityException ex) {
            Log.d(TAG, "create failed:", ex);
            // fall through
        }
        return null;
    }

    /**
     * Sets the data source as a content Uri.
     *
     * @param context the Context to use when resolving the Uri
     * @param uri the Content URI of the data you want to play
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void setDataSource(@NonNull Context context, @NonNull Uri uri)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(context, uri, null, null);
    }

    /**
     * Sets the data source as a content Uri.
     *
     * To provide cookies for the subsequent HTTP requests, you can install your own default cookie
     * handler and use other variants of setDataSource APIs instead. Alternatively, you can use
     * this API to pass the cookies as a list of HttpCookie. If the app has not installed
     * a CookieHandler already, this API creates a CookieManager and populates its CookieStore with
     * the provided cookies. If the app has installed its own handler already, this API requires the
     * handler to be of CookieManager type such that the API can update the manager’s CookieStore.
     *
     * <p><strong>Note</strong> that the cross domain redirection is allowed by default,
     * but that can be changed with key/value pairs through the headers parameter with
     * "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value to
     * disallow or allow cross domain redirection.
     *
     * @param context the Context to use when resolving the Uri
     * @param uri the Content URI of the data you want to play
     * @param headers the headers to be sent together with the request for the data
     *                The headers must not include cookies. Instead, use the cookies param.
     * @param cookies the cookies to be sent together with the request
     * @throws IllegalArgumentException if cookies are provided and the installed handler is not
     *                                  a CookieManager
     * @throws IllegalStateException    if it is called in an invalid state
     * @throws NullPointerException     if context or uri is null
     * @throws IOException              if uri has a file scheme and an I/O error occurs
     */
    public void setDataSource(@NonNull Context context, @NonNull Uri uri,
            @Nullable Map<String, String> headers, @Nullable List<HttpCookie> cookies)
            throws IOException {
        if (context == null) {
            throw new NullPointerException("context param can not be null.");
        }

        if (uri == null) {
            throw new NullPointerException("uri param can not be null.");
        }

        if (cookies != null) {
            CookieHandler cookieHandler = CookieHandler.getDefault();
            if (cookieHandler != null && !(cookieHandler instanceof CookieManager)) {
                throw new IllegalArgumentException("The cookie handler has to be of CookieManager "
                        + "type when cookies are provided.");
            }
        }

        // The context and URI usually belong to the calling user. Get a resolver for that user
        // and strip out the userId from the URI if present.
        final ContentResolver resolver = context.getContentResolver();
        final String scheme = uri.getScheme();
        final String authority = ContentProvider.getAuthorityWithoutUserId(uri.getAuthority());
        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            setDataSource(uri.getPath());
            return;
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                && Settings.AUTHORITY.equals(authority)) {
            // Try cached ringtone first since the actual provider may not be
            // encryption aware, or it may be stored on CE media storage
            final int type = RingtoneManager.getDefaultType(uri);
            final Uri cacheUri = RingtoneManager.getCacheForType(type, context.getUserId());
            final Uri actualUri = RingtoneManager.getActualDefaultRingtoneUri(context, type);
            if (attemptDataSource(resolver, cacheUri)) {
                return;
            } else if (attemptDataSource(resolver, actualUri)) {
                return;
            } else {
                setDataSource(uri.toString(), headers, cookies);
            }
        } else {
            // Try requested Uri locally first, or fallback to media server
            if (attemptDataSource(resolver, uri)) {
                return;
            } else {
                setDataSource(uri.toString(), headers, cookies);
            }
        }
    }

    /**
     * Sets the data source as a content Uri.
     *
     * <p><strong>Note</strong> that the cross domain redirection is allowed by default,
     * but that can be changed with key/value pairs through the headers parameter with
     * "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value to
     * disallow or allow cross domain redirection.
     *
     * @param context the Context to use when resolving the Uri
     * @param uri the Content URI of the data you want to play
     * @param headers the headers to be sent together with the request for the data
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void setDataSource(@NonNull Context context, @NonNull Uri uri,
            @Nullable Map<String, String> headers)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(context, uri, headers, null);
    }

    private boolean attemptDataSource(ContentResolver resolver, Uri uri) {
        boolean optimize = SystemProperties.getBoolean("fuse.sys.transcode_player_optimize",
                false);
        Bundle opts = new Bundle();
        opts.putBoolean("android.provider.extra.ACCEPT_ORIGINAL_MEDIA_FORMAT", true);
        try (AssetFileDescriptor afd = optimize
                ? resolver.openTypedAssetFileDescriptor(uri, "*/*", opts)
                : resolver.openAssetFileDescriptor(uri, "r")) {
            setDataSource(afd);
            return true;
        } catch (NullPointerException | SecurityException | IOException ex) {
            return false;
        }
    }

    /**
     * Sets the data source (file-path or http/rtsp URL) to use.
     *
     * <p>When <code>path</code> refers to a local file, the file may actually be opened by a
     * process other than the calling application.  This implies that the pathname
     * should be an absolute path (as any other process runs with unspecified current working
     * directory), and that the pathname should reference a world-readable file.
     * As an alternative, the application could first open the file for reading,
     * and then use the file descriptor form {@link #setDataSource(FileDescriptor)}.
     *
     * @param path the path of the file, or the http/rtsp URL of the stream you want to play
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void setDataSource(String path)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(path, null, null);
    }

    /**
     * Sets the data source (file-path or http/rtsp URL) to use.
     *
     * @param path the path of the file, or the http/rtsp URL of the stream you want to play
     * @param headers the headers associated with the http request for the stream you want to play
     * @throws IllegalStateException if it is called in an invalid state
     * @hide pending API council
     */
    @UnsupportedAppUsage
    public void setDataSource(String path, Map<String, String> headers)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        setDataSource(path, headers, null);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void setDataSource(String path, Map<String, String> headers, List<HttpCookie> cookies)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException
    {
        String[] keys = null;
        String[] values = null;

        if (headers != null) {
            keys = new String[headers.size()];
            values = new String[headers.size()];

            int i = 0;
            for (Map.Entry<String, String> entry: headers.entrySet()) {
                keys[i] = entry.getKey();
                values[i] = entry.getValue();
                ++i;
            }
        }
        setDataSource(path, keys, values, cookies);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void setDataSource(String path, String[] keys, String[] values,
            List<HttpCookie> cookies)
            throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        final Uri uri = Uri.parse(path);
        final String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            path = uri.getPath();
        } else if (scheme != null) {
            // handle non-file sources
            nativeSetDataSource(
                MediaHTTPService.createHttpServiceBinderIfNecessary(path, cookies),
                path,
                keys,
                values);
            return;
        }

        final File file = new File(path);
        try (FileInputStream is = new FileInputStream(file)) {
            setDataSource(is.getFD());
        }
    }

    private native void nativeSetDataSource(
        IBinder httpServiceBinder, String path, String[] keys, String[] values)
        throws IOException, IllegalArgumentException, SecurityException, IllegalStateException;

    /**
     * Sets the data source (AssetFileDescriptor) to use. It is the caller's
     * responsibility to close the file descriptor. It is safe to do so as soon
     * as this call returns.
     *
     * @param afd the AssetFileDescriptor for the file you want to play
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if afd is not a valid AssetFileDescriptor
     * @throws IOException if afd can not be read
     */
    public void setDataSource(@NonNull AssetFileDescriptor afd)
            throws IOException, IllegalArgumentException, IllegalStateException {
        Preconditions.checkNotNull(afd);
        // Note: using getDeclaredLength so that our behavior is the same
        // as previous versions when the content provider is returning
        // a full file.
        if (afd.getDeclaredLength() < 0) {
            setDataSource(afd.getFileDescriptor());
        } else {
            setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
        }
    }

    /**
     * Sets the data source (FileDescriptor) to use. It is the caller's responsibility
     * to close the file descriptor. It is safe to do so as soon as this call returns.
     *
     * @param fd the FileDescriptor for the file you want to play
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if fd is not a valid FileDescriptor
     * @throws IOException if fd can not be read
     */
    public void setDataSource(FileDescriptor fd)
            throws IOException, IllegalArgumentException, IllegalStateException {
        // intentionally less than LONG_MAX
        setDataSource(fd, 0, 0x7ffffffffffffffL);
    }

    /**
     * Sets the data source (FileDescriptor) to use.  The FileDescriptor must be
     * seekable (N.B. a LocalSocket is not seekable). It is the caller's responsibility
     * to close the file descriptor. It is safe to do so as soon as this call returns.
     *
     * @param fd the FileDescriptor for the file you want to play
     * @param offset the offset into the file where the data to be played starts, in bytes
     * @param length the length in bytes of the data to be played
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if fd is not a valid FileDescriptor
     * @throws IOException if fd can not be read
     */
    public void setDataSource(FileDescriptor fd, long offset, long length)
            throws IOException, IllegalArgumentException, IllegalStateException {
        try (ParcelFileDescriptor modernFd = FileUtils.convertToModernFd(fd)) {
            if (modernFd == null) {
                _setDataSource(fd, offset, length);
            } else {
                _setDataSource(modernFd.getFileDescriptor(), offset, length);
            }
        } catch (IOException e) {
            Log.w(TAG, "Ignoring IO error while setting data source", e);
        }
    }

    private native void _setDataSource(FileDescriptor fd, long offset, long length)
            throws IOException, IllegalArgumentException, IllegalStateException;

    /**
     * Sets the data source (MediaDataSource) to use.
     *
     * @param dataSource the MediaDataSource for the media you want to play
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if dataSource is not a valid MediaDataSource
     */
    public void setDataSource(MediaDataSource dataSource)
            throws IllegalArgumentException, IllegalStateException {
        _setDataSource(dataSource);
    }

    private native void _setDataSource(MediaDataSource dataSource)
          throws IllegalArgumentException, IllegalStateException;

    /**
     * Prepares the player for playback, synchronously.
     *
     * After setting the datasource and the display surface, you need to either
     * call prepare() or prepareAsync(). For files, it is OK to call prepare(),
     * which blocks until MediaPlayer is ready for playback.
     *
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void prepare() throws IOException, IllegalStateException {
        _prepare();
        scanInternalSubtitleTracks();

        // DrmInfo, if any, has been resolved by now.
        synchronized (mDrmLock) {
            mDrmInfoResolved = true;
        }
    }

    private native void _prepare() throws IOException, IllegalStateException;

    /**
     * Prepares the player for playback, asynchronously.
     *
     * After setting the datasource and the display surface, you need to either
     * call prepare() or prepareAsync(). For streams, you should call prepareAsync(),
     * which returns immediately, rather than blocking until enough data has been
     * buffered.
     *
     * @throws IllegalStateException if it is called in an invalid state
     */
    public native void prepareAsync() throws IllegalStateException;

    /**
     * Starts or resumes playback. If playback had previously been paused,
     * playback will continue from where it was paused. If playback had
     * been stopped, or never started before, playback will start at the
     * beginning.
     *
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void start() throws IllegalStateException {
        //FIXME use lambda to pass startImpl to superclass
        final int delay = getStartDelayMs();
        if (delay == 0) {
            startImpl();
        } else {
            new Thread() {
                public void run() {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    baseSetStartDelayMs(0);
                    try {
                        startImpl();
                    } catch (IllegalStateException e) {
                        // fail silently for a state exception when it is happening after
                        // a delayed start, as the player state could have changed between the
                        // call to start() and the execution of startImpl()
                    }
                }
            }.start();
        }
    }

    private void startImpl() {
        baseStart(0); // unknown device at this point
        stayAwake(true);
        tryToEnableNativeRoutingCallback();
        _start();
    }

    private native void _start() throws IllegalStateException;


    private int getAudioStreamType() {
        if (mStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            mStreamType = _getAudioStreamType();
        }
        return mStreamType;
    }

    private native int _getAudioStreamType() throws IllegalStateException;

    /**
     * Stops playback after playback has been started or paused.
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    public void stop() throws IllegalStateException {
        stayAwake(false);
        _stop();
        baseStop();
        tryToDisableNativeRoutingCallback();
    }

    private native void _stop() throws IllegalStateException;

    /**
     * Pauses playback. Call start() to resume.
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    public void pause() throws IllegalStateException {
        stayAwake(false);
        _pause();
        basePause();
    }

    private native void _pause() throws IllegalStateException;

    @Override
    void playerStart() {
        start();
    }

    @Override
    void playerPause() {
        pause();
    }

    @Override
    void playerStop() {
        stop();
    }

    @Override
    /* package */ int playerApplyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        return native_applyVolumeShaper(configuration, operation);
    }

    @Override
    /* package */ @Nullable VolumeShaper.State playerGetVolumeShaperState(int id) {
        return native_getVolumeShaperState(id);
    }

    @Override
    public @NonNull VolumeShaper createVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration) {
        return new VolumeShaper(configuration, this);
    }

    private native int native_applyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation);

    private native @Nullable VolumeShaper.State native_getVolumeShaperState(int id);

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the output from this MediaPlayer.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio sink or source.
     *  If deviceInfo is null, default routing is restored.
     * @return true if succesful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio device.
     */
    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo != null && !deviceInfo.isSink()) {
            return false;
        }
        int preferredDeviceId = deviceInfo != null ? deviceInfo.getId() : 0;
        boolean status = native_setOutputDevice(preferredDeviceId);
        if (status == true) {
            synchronized (this) {
                mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    /**
     * Returns the selected output specified by {@link #setPreferredDevice}. Note that this
     * is not guaranteed to correspond to the actual device being used for playback.
     */
    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this MediaPlayer
     * Note: The query is only valid if the MediaPlayer is currently playing.
     * If the player is not playing, the returned device can be null or correspond to previously
     * selected device when the player was last active.
     */
    @Override
    public AudioDeviceInfo getRoutedDevice() {
        int deviceId = native_getRoutedDeviceId();
        if (deviceId == 0) {
            return null;
        }
        return AudioManager.getDeviceForPortId(deviceId, AudioManager.GET_DEVICES_OUTPUTS);
    }


    /**
     * Sends device list change notification to all listeners.
     */
    private void broadcastRoutingChange() {
        AudioManager.resetAudioPortGeneration();
        synchronized (mRoutingChangeListeners) {
            // Prevent the case where an event is triggered by registering a routing change
            // listener via the media player.
            if (mEnableSelfRoutingMonitor) {
                baseUpdateDeviceId(getRoutedDevice());
            }
            for (NativeRoutingEventHandlerDelegate delegate
                    : mRoutingChangeListeners.values()) {
                delegate.notifyClient();
            }
        }
    }

    /**
     * Call BEFORE adding a routing callback handler and when enabling self routing listener
     * @return returns true for success, false otherwise.
     */
    @GuardedBy("mRoutingChangeListeners")
    private boolean testEnableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0 && !mEnableSelfRoutingMonitor) {
            try {
                native_enableDeviceCallback(true);
                return true;
            } catch (IllegalStateException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "testEnableNativeRoutingCallbacks failed", e);
                }
            }
        }
        return false;
    }

    private void  tryToEnableNativeRoutingCallback() {
        synchronized (mRoutingChangeListeners) {
            if (!mEnableSelfRoutingMonitor) {
                mEnableSelfRoutingMonitor = testEnableNativeRoutingCallbacksLocked();
            }
        }
    }

    private void tryToDisableNativeRoutingCallback() {
        synchronized (mRoutingChangeListeners) {
            if (mEnableSelfRoutingMonitor) {
                mEnableSelfRoutingMonitor = false;
                testDisableNativeRoutingCallbacksLocked();
            }
        }
    }

    /*
     * Call AFTER removing a routing callback handler and when disabling self routing listener
     */
    @GuardedBy("mRoutingChangeListeners")
    private void testDisableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0 && !mEnableSelfRoutingMonitor) {
            try {
                native_enableDeviceCallback(false);
            } catch (IllegalStateException e) {
                // Fail silently as media player state could have changed in between stop
                // and disabling routing callback
            }
        }
    }

    /**
     * The list of AudioRouting.OnRoutingChangedListener interfaces added (with
     * {@link #addOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener, Handler)}
     * by an app to receive (re)routing notifications.
     */
    @GuardedBy("mRoutingChangeListeners")
    private ArrayMap<AudioRouting.OnRoutingChangedListener,
            NativeRoutingEventHandlerDelegate> mRoutingChangeListeners = new ArrayMap<>();

    @GuardedBy("mRoutingChangeListeners")
    private boolean mEnableSelfRoutingMonitor;

    /**
     * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of routing
     * changes on this MediaPlayer.
     * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
     * notifications of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the handler on the main looper will be used.
     */
    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
            Handler handler) {
        synchronized (mRoutingChangeListeners) {
            if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
                mEnableSelfRoutingMonitor = testEnableNativeRoutingCallbacksLocked();
                mRoutingChangeListeners.put(
                        listener, new NativeRoutingEventHandlerDelegate(this, listener,
                                handler != null ? handler : mEventHandler));
            }
        }
    }

    /**
     * Removes an {@link AudioRouting.OnRoutingChangedListener} which has been previously added
     * to receive rerouting notifications.
     * @param listener The previously added {@link AudioRouting.OnRoutingChangedListener} interface
     * to remove.
     */
    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        synchronized (mRoutingChangeListeners) {
            if (mRoutingChangeListeners.containsKey(listener)) {
                mRoutingChangeListeners.remove(listener);
            }
            testDisableNativeRoutingCallbacksLocked();
        }
    }

    private native final boolean native_setOutputDevice(int deviceId);
    private native final int native_getRoutedDeviceId();
    private native final void native_enableDeviceCallback(boolean enabled);

    /**
     * Set the low-level power management behavior for this MediaPlayer.  This
     * can be used when the MediaPlayer is not playing through a SurfaceHolder
     * set with {@link #setDisplay(SurfaceHolder)} and thus can use the
     * high-level {@link #setScreenOnWhilePlaying(boolean)} feature.
     *
     * <p>This function has the MediaPlayer access the low-level power manager
     * service to control the device's power usage while playing is occurring.
     * The parameter is a combination of {@link android.os.PowerManager} wake flags.
     * Use of this method requires {@link android.Manifest.permission#WAKE_LOCK}
     * permission.
     * By default, no attempt is made to keep the device awake during playback.
     *
     * @param context the Context to use
     * @param mode    the power/wake mode to set
     * @see android.os.PowerManager
     */
    public void setWakeMode(Context context, int mode) {
        boolean washeld = false;

        /* Disable persistant wakelocks in media player based on property */
        if (SystemProperties.getBoolean("audio.offload.ignore_setawake", false) == true) {
            Log.w(TAG, "IGNORING setWakeMode " + mode);
            return;
        }

        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                washeld = true;
                mWakeLock.release();
            }
            mWakeLock = null;
        }

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(mode|PowerManager.ON_AFTER_RELEASE, MediaPlayer.class.getName());
        mWakeLock.setReferenceCounted(false);
        if (washeld) {
            mWakeLock.acquire();
        }
    }

    /**
     * Control whether we should use the attached SurfaceHolder to keep the
     * screen on while video playback is occurring.  This is the preferred
     * method over {@link #setWakeMode} where possible, since it doesn't
     * require that the application have permission for low-level wake lock
     * access.
     *
     * @param screenOn Supply true to keep the screen on, false to allow it
     * to turn off.
     */
    public void setScreenOnWhilePlaying(boolean screenOn) {
        if (mScreenOnWhilePlaying != screenOn) {
            if (screenOn && mSurfaceHolder == null) {
                Log.w(TAG, "setScreenOnWhilePlaying(true) is ineffective without a SurfaceHolder");
            }
            mScreenOnWhilePlaying = screenOn;
            updateSurfaceScreenOn();
        }
    }

    private void stayAwake(boolean awake) {
        if (mWakeLock != null) {
            if (awake && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            } else if (!awake && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        mStayAwake = awake;
        updateSurfaceScreenOn();
    }

    private void updateSurfaceScreenOn() {
        if (mSurfaceHolder != null) {
            mSurfaceHolder.setKeepScreenOn(mScreenOnWhilePlaying && mStayAwake);
        }
    }

    /**
     * Returns the width of the video.
     *
     * @return the width of the video, or 0 if there is no video,
     * no display surface was set, or the width has not been determined
     * yet. The OnVideoSizeChangedListener can be registered via
     * {@link #setOnVideoSizeChangedListener(OnVideoSizeChangedListener)}
     * to provide a notification when the width is available.
     */
    public native int getVideoWidth();

    /**
     * Returns the height of the video.
     *
     * @return the height of the video, or 0 if there is no video,
     * no display surface was set, or the height has not been determined
     * yet. The OnVideoSizeChangedListener can be registered via
     * {@link #setOnVideoSizeChangedListener(OnVideoSizeChangedListener)}
     * to provide a notification when the height is available.
     */
    public native int getVideoHeight();

    /**
     * Return Metrics data about the current player.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being handled by this instance of MediaPlayer
     * The attributes are descibed in {@link MetricsConstants}.
     *
     *  Additional vendor-specific fields may also be present in
     *  the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();

    /**
     * Checks whether the MediaPlayer is playing.
     *
     * @return true if currently playing, false otherwise
     * @throws IllegalStateException if the internal player engine has not been
     * initialized or has been released.
     */
    public native boolean isPlaying();

    /**
     * Change playback speed of audio by resampling the audio.
     * <p>
     * Specifies resampling as audio mode for variable rate playback, i.e.,
     * resample the waveform based on the requested playback rate to get
     * a new waveform, and play back the new waveform at the original sampling
     * frequency.
     * When rate is larger than 1.0, pitch becomes higher.
     * When rate is smaller than 1.0, pitch becomes lower.
     *
     * @hide
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_RESAMPLE = 2;

    /**
     * Change playback speed of audio without changing its pitch.
     * <p>
     * Specifies time stretching as audio mode for variable rate playback.
     * Time stretching changes the duration of the audio samples without
     * affecting its pitch.
     * <p>
     * This mode is only supported for a limited range of playback speed factors,
     * e.g. between 1/2x and 2x.
     *
     * @hide
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_STRETCH = 1;

    /**
     * Change playback speed of audio without changing its pitch, and
     * possibly mute audio if time stretching is not supported for the playback
     * speed.
     * <p>
     * Try to keep audio pitch when changing the playback rate, but allow the
     * system to determine how to change audio playback if the rate is out
     * of range.
     *
     * @hide
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_DEFAULT = 0;

    /** @hide */
    @IntDef(
        value = {
            PLAYBACK_RATE_AUDIO_MODE_DEFAULT,
            PLAYBACK_RATE_AUDIO_MODE_STRETCH,
            PLAYBACK_RATE_AUDIO_MODE_RESAMPLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlaybackRateAudioMode {}

    /**
     * Sets playback rate and audio mode.
     *
     * @param rate the ratio between desired playback rate and normal one.
     * @param audioMode audio playback mode. Must be one of the supported
     * audio modes.
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     * @throws IllegalArgumentException if audioMode is not supported.
     *
     * @hide
     */
    @NonNull
    public PlaybackParams easyPlaybackParams(float rate, @PlaybackRateAudioMode int audioMode) {
        PlaybackParams params = new PlaybackParams();
        params.allowDefaults();
        switch (audioMode) {
        case PLAYBACK_RATE_AUDIO_MODE_DEFAULT:
            params.setSpeed(rate).setPitch(1.0f);
            break;
        case PLAYBACK_RATE_AUDIO_MODE_STRETCH:
            params.setSpeed(rate).setPitch(1.0f)
                    .setAudioFallbackMode(params.AUDIO_FALLBACK_MODE_FAIL);
            break;
        case PLAYBACK_RATE_AUDIO_MODE_RESAMPLE:
            params.setSpeed(rate).setPitch(rate);
            break;
        default:
            final String msg = "Audio playback mode " + audioMode + " is not supported";
            throw new IllegalArgumentException(msg);
        }
        return params;
    }

    /**
     * Sets playback rate using {@link PlaybackParams}. The object sets its internal
     * PlaybackParams to the input, except that the object remembers previous speed
     * when input speed is zero. This allows the object to resume at previous speed
     * when start() is called. Calling it before the object is prepared does not change
     * the object state. After the object is prepared, calling it with zero speed is
     * equivalent to calling pause(). After the object is prepared, calling it with
     * non-zero speed is equivalent to calling start().
     *
     * @param params the playback params.
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized or has been released.
     * @throws IllegalArgumentException if params is not supported.
     */
    public native void setPlaybackParams(@NonNull PlaybackParams params);

    /**
     * Gets the playback params, containing the current playback rate.
     *
     * @return the playback params.
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    @NonNull
    public native PlaybackParams getPlaybackParams();

    /**
     * Sets A/V sync mode.
     *
     * @param params the A/V sync params to apply
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     * @throws IllegalArgumentException if params are not supported.
     */
    public native void setSyncParams(@NonNull SyncParams params);

    /**
     * Gets the A/V sync mode.
     *
     * @return the A/V sync params
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    @NonNull
    public native SyncParams getSyncParams();

    /**
     * Seek modes used in method seekTo(long, int) to move media position
     * to a specified location.
     *
     * Do not change these mode values without updating their counterparts
     * in include/media/IMediaSource.h!
     */
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * right before or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_PREVIOUS_SYNC    = 0x00;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * right after or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_NEXT_SYNC        = 0x01;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a sync (or key) frame associated with a data source that is located
     * closest to (in time) or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_CLOSEST_SYNC     = 0x02;
    /**
     * This mode is used with {@link #seekTo(long, int)} to move media position to
     * a frame (not necessarily a key frame) associated with a data source that
     * is located closest to or at the given time.
     *
     * @see #seekTo(long, int)
     */
    public static final int SEEK_CLOSEST          = 0x03;

    /** @hide */
    @IntDef(
        value = {
            SEEK_PREVIOUS_SYNC,
            SEEK_NEXT_SYNC,
            SEEK_CLOSEST_SYNC,
            SEEK_CLOSEST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SeekMode {}

    private native final void _seekTo(long msec, int mode);

    /**
     * Moves the media to specified time position by considering the given mode.
     * <p>
     * When seekTo is finished, the user will be notified via OnSeekComplete supplied by the user.
     * There is at most one active seekTo processed at any time. If there is a to-be-completed
     * seekTo, new seekTo requests will be queued in such a way that only the last request
     * is kept. When current seekTo is completed, the queued request will be processed if
     * that request is different from just-finished seekTo operation, i.e., the requested
     * position or mode is different.
     *
     * @param msec the offset in milliseconds from the start to seek to.
     * When seeking to the given time position, there is no guarantee that the data source
     * has a frame located at the position. When this happens, a frame nearby will be rendered.
     * If msec is negative, time position zero will be used.
     * If msec is larger than duration, duration will be used.
     * @param mode the mode indicating where exactly to seek to.
     * Use {@link #SEEK_PREVIOUS_SYNC} if one wants to seek to a sync frame
     * that has a timestamp earlier than or the same as msec. Use
     * {@link #SEEK_NEXT_SYNC} if one wants to seek to a sync frame
     * that has a timestamp later than or the same as msec. Use
     * {@link #SEEK_CLOSEST_SYNC} if one wants to seek to a sync frame
     * that has a timestamp closest to or the same as msec. Use
     * {@link #SEEK_CLOSEST} if one wants to seek to a frame that may
     * or may not be a sync frame but is closest to or the same as msec.
     * {@link #SEEK_CLOSEST} often has larger performance overhead compared
     * to the other options if there is no sync frame located at msec.
     * @throws IllegalStateException if the internal player engine has not been
     * initialized
     * @throws IllegalArgumentException if the mode is invalid.
     */
    public void seekTo(long msec, @SeekMode int mode) {
        if (mode < SEEK_PREVIOUS_SYNC || mode > SEEK_CLOSEST) {
            final String msg = "Illegal seek mode: " + mode;
            throw new IllegalArgumentException(msg);
        }
        // TODO: pass long to native, instead of truncating here.
        if (msec > Integer.MAX_VALUE) {
            Log.w(TAG, "seekTo offset " + msec + " is too large, cap to " + Integer.MAX_VALUE);
            msec = Integer.MAX_VALUE;
        } else if (msec < Integer.MIN_VALUE) {
            Log.w(TAG, "seekTo offset " + msec + " is too small, cap to " + Integer.MIN_VALUE);
            msec = Integer.MIN_VALUE;
        }
        _seekTo(msec, mode);
    }

    /**
     * Seeks to specified time position.
     * Same as {@link #seekTo(long, int)} with {@code mode = SEEK_PREVIOUS_SYNC}.
     *
     * @param msec the offset in milliseconds from the start to seek to
     * @throws IllegalStateException if the internal player engine has not been
     * initialized
     */
    public void seekTo(int msec) throws IllegalStateException {
        seekTo(msec, SEEK_PREVIOUS_SYNC /* mode */);
    }

    /**
     * Get current playback position as a {@link MediaTimestamp}.
     * <p>
     * The MediaTimestamp represents how the media time correlates to the system time in
     * a linear fashion using an anchor and a clock rate. During regular playback, the media
     * time moves fairly constantly (though the anchor frame may be rebased to a current
     * system time, the linear correlation stays steady). Therefore, this method does not
     * need to be called often.
     * <p>
     * To help users get current playback position, this method always anchors the timestamp
     * to the current {@link System#nanoTime system time}, so
     * {@link MediaTimestamp#getAnchorMediaTimeUs} can be used as current playback position.
     *
     * @return a MediaTimestamp object if a timestamp is available, or {@code null} if no timestamp
     *         is available, e.g. because the media player has not been initialized.
     *
     * @see MediaTimestamp
     */
    @Nullable
    public MediaTimestamp getTimestamp()
    {
        try {
            // TODO: get the timestamp from native side
            return new MediaTimestamp(
                    getCurrentPosition() * 1000L,
                    System.nanoTime(),
                    isPlaying() ? getPlaybackParams().getSpeed() : 0.f);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Gets the current playback position.
     *
     * @return the current position in milliseconds
     */
    public native int getCurrentPosition();

    /**
     * Gets the duration of the file.
     *
     * @return the duration in milliseconds, if no duration is available
     *         (for example, if streaming live content), -1 is returned.
     */
    public native int getDuration();

    /**
     * Gets the media metadata.
     *
     * @param update_only controls whether the full set of available
     * metadata is returned or just the set that changed since the
     * last call. See {@see #METADATA_UPDATE_ONLY} and {@see
     * #METADATA_ALL}.
     *
     * @param apply_filter if true only metadata that matches the
     * filter is returned. See {@see #APPLY_METADATA_FILTER} and {@see
     * #BYPASS_METADATA_FILTER}.
     *
     * @return The metadata, possibly empty. null if an error occured.
     // FIXME: unhide.
     * {@hide}
     */
    @UnsupportedAppUsage
    public Metadata getMetadata(final boolean update_only,
                                final boolean apply_filter) {
        Parcel reply = Parcel.obtain();
        Metadata data = new Metadata();

        if (!native_getMetadata(update_only, apply_filter, reply)) {
            reply.recycle();
            return null;
        }

        // Metadata takes over the parcel, don't recycle it unless
        // there is an error.
        if (!data.parse(reply)) {
            reply.recycle();
            return null;
        }
        return data;
    }

    /**
     * Set a filter for the metadata update notification and update
     * retrieval. The caller provides 2 set of metadata keys, allowed
     * and blocked. The blocked set always takes precedence over the
     * allowed one.
     * Metadata.MATCH_ALL and Metadata.MATCH_NONE are 2 sets available as
     * shorthands to allow/block all or no metadata.
     *
     * By default, there is no filter set.
     *
     * @param allow Is the set of metadata the client is interested
     *              in receiving new notifications for.
     * @param block Is the set of metadata the client is not interested
     *              in receiving new notifications for.
     * @return The call status code.
     *
     // FIXME: unhide.
     * {@hide}
     */
    public int setMetadataFilter(Set<Integer> allow, Set<Integer> block) {
        // Do our serialization manually instead of calling
        // Parcel.writeArray since the sets are made of the same type
        // we avoid paying the price of calling writeValue (used by
        // writeArray) which burns an extra int per element to encode
        // the type.
        Parcel request =  newRequest();

        // The parcel starts already with an interface token. There
        // are 2 filters. Each one starts with a 4bytes number to
        // store the len followed by a number of int (4 bytes as well)
        // representing the metadata type.
        int capacity = request.dataSize() + 4 * (1 + allow.size() + 1 + block.size());

        if (request.dataCapacity() < capacity) {
            request.setDataCapacity(capacity);
        }

        request.writeInt(allow.size());
        for(Integer t: allow) {
            request.writeInt(t);
        }
        request.writeInt(block.size());
        for(Integer t: block) {
            request.writeInt(t);
        }
        return native_setMetadataFilter(request);
    }

    /**
     * Set the MediaPlayer to start when this MediaPlayer finishes playback
     * (i.e. reaches the end of the stream).
     * The media framework will attempt to transition from this player to
     * the next as seamlessly as possible. The next player can be set at
     * any time before completion, but shall be after setDataSource has been
     * called successfully. The next player must be prepared by the
     * app, and the application should not call start() on it.
     * The next MediaPlayer must be different from 'this'. An exception
     * will be thrown if next == this.
     * The application may call setNextMediaPlayer(null) to indicate no
     * next player should be started at the end of playback.
     * If the current player is looping, it will keep looping and the next
     * player will not be started.
     *
     * @param next the player to start after this one completes playback.
     *
     */
    public native void setNextMediaPlayer(MediaPlayer next);

    /**
     * Releases resources associated with this MediaPlayer object.
     *
     * <p>You must call this method once the instance is no longer required.
     */
    public void release() {
        baseRelease();
        stayAwake(false);
        updateSurfaceScreenOn();
        mOnPreparedListener = null;
        mOnBufferingUpdateListener = null;
        mOnCompletionListener = null;
        mOnSeekCompleteListener = null;
        mOnErrorListener = null;
        mOnInfoListener = null;
        mOnVideoSizeChangedListener = null;
        mOnTimedTextListener = null;
        mOnRtpRxNoticeListener = null;
        mOnRtpRxNoticeExecutor = null;
        synchronized (mTimeProviderLock) {
            if (mTimeProvider != null) {
                mTimeProvider.close();
                mTimeProvider = null;
            }
        }
        synchronized(this) {
            mSubtitleDataListenerDisabled = false;
            mExtSubtitleDataListener = null;
            mExtSubtitleDataHandler = null;
            mOnMediaTimeDiscontinuityListener = null;
            mOnMediaTimeDiscontinuityHandler = null;
        }

        // Modular DRM clean up
        mOnDrmConfigHelper = null;
        mOnDrmInfoHandlerDelegate = null;
        mOnDrmPreparedHandlerDelegate = null;
        resetDrmState();

        _release();
    }

    private native void _release();

    /**
     * Resets the MediaPlayer to its uninitialized state. After calling
     * this method, you will have to initialize it again by setting the
     * data source and calling prepare().
     */
    public void reset() {
        mSelectedSubtitleTrackIndex = -1;
        synchronized(mOpenSubtitleSources) {
            for (final InputStream is: mOpenSubtitleSources) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
            mOpenSubtitleSources.clear();
        }
        if (mSubtitleController != null) {
            mSubtitleController.reset();
        }
        synchronized (mTimeProviderLock) {
            if (mTimeProvider != null) {
                mTimeProvider.close();
                mTimeProvider = null;
            }
        }

        stayAwake(false);
        _reset();
        // make sure none of the listeners get called anymore
        if (mEventHandler != null) {
            mEventHandler.removeCallbacksAndMessages(null);
        }

        synchronized (mIndexTrackPairs) {
            mIndexTrackPairs.clear();
            mInbandTrackIndices.clear();
        };

        resetDrmState();
    }

    private native void _reset();

    /**
     * Set up a timer for {@link #TimeProvider}. {@link #TimeProvider} will be
     * notified when the presentation time reaches (becomes greater than or equal to)
     * the value specified.
     *
     * @param mediaTimeUs presentation time to get timed event callback at
     * @hide
     */
    public void notifyAt(long mediaTimeUs) {
        _notifyAt(mediaTimeUs);
    }

    private native void _notifyAt(long mediaTimeUs);

    /**
     * Sets the audio stream type for this MediaPlayer. See {@link AudioManager}
     * for a list of stream types. Must call this method before prepare() or
     * prepareAsync() in order for the target stream type to become effective
     * thereafter.
     *
     * @param streamtype the audio stream type
     * @deprecated use {@link #setAudioAttributes(AudioAttributes)}
     * @see android.media.AudioManager
     */
    public void setAudioStreamType(int streamtype) {
        deprecateStreamTypeForPlayback(streamtype, "MediaPlayer", "setAudioStreamType()");
        baseUpdateAudioAttributes(
                new AudioAttributes.Builder().setInternalLegacyStreamType(streamtype).build());
        _setAudioStreamType(streamtype);
        mStreamType = streamtype;
    }

    private native void _setAudioStreamType(int streamtype);

    // Keep KEY_PARAMETER_* in sync with include/media/mediaplayer.h
    private final static int KEY_PARAMETER_AUDIO_ATTRIBUTES = 1400;
    /**
     * Sets the parameter indicated by key.
     * @param key key indicates the parameter to be set.
     * @param value value of the parameter to be set.
     * @return true if the parameter is set successfully, false otherwise
     * {@hide}
     */
    @UnsupportedAppUsage
    private native boolean setParameter(int key, Parcel value);

    /**
     * Sets the audio attributes for this MediaPlayer.
     * See {@link AudioAttributes} for how to build and configure an instance of this class.
     * You must call this method before {@link #prepare()} or {@link #prepareAsync()} in order
     * for the audio attributes to become effective thereafter.
     * @param attributes a non-null set of audio attributes
     */
    public void setAudioAttributes(AudioAttributes attributes) throws IllegalArgumentException {
        if (attributes == null) {
            final String msg = "Cannot set AudioAttributes to null";
            throw new IllegalArgumentException(msg);
        }
        baseUpdateAudioAttributes(attributes);
        Parcel pattributes = Parcel.obtain();
        attributes.writeToParcel(pattributes, AudioAttributes.FLATTEN_TAGS);
        setParameter(KEY_PARAMETER_AUDIO_ATTRIBUTES, pattributes);
        pattributes.recycle();
    }

    /**
     * Sets the player to be looping or non-looping.
     *
     * @param looping whether to loop or not
     */
    public native void setLooping(boolean looping);

    /**
     * Checks whether the MediaPlayer is looping or non-looping.
     *
     * @return true if the MediaPlayer is currently looping, false otherwise
     */
    public native boolean isLooping();

    /**
     * Sets the volume on this player.
     * This API is recommended for balancing the output of audio streams
     * within an application. Unless you are writing an application to
     * control user settings, this API should be used in preference to
     * {@link AudioManager#setStreamVolume(int, int, int)} which sets the volume of ALL streams of
     * a particular type. Note that the passed volume values are raw scalars in range 0.0 to 1.0.
     * UI controls should be scaled logarithmically.
     *
     * @param leftVolume left volume scalar
     * @param rightVolume right volume scalar
     */
    /*
     * FIXME: Merge this into javadoc comment above when setVolume(float) is not @hide.
     * The single parameter form below is preferred if the channel volumes don't need
     * to be set independently.
     */
    public void setVolume(float leftVolume, float rightVolume) {
        baseSetVolume(leftVolume, rightVolume);
    }

    @Override
    void playerSetVolume(boolean muting, float leftVolume, float rightVolume) {
        _setVolume(muting ? 0.0f : leftVolume, muting ? 0.0f : rightVolume);
    }

    private native void _setVolume(float leftVolume, float rightVolume);

    /**
     * Similar, excepts sets volume of all channels to same value.
     * @hide
     */
    public void setVolume(float volume) {
        setVolume(volume, volume);
    }

    /**
     * Sets the audio session ID.
     *
     * @param sessionId the audio session ID.
     * The audio session ID is a system wide unique identifier for the audio stream played by
     * this MediaPlayer instance.
     * The primary use of the audio session ID  is to associate audio effects to a particular
     * instance of MediaPlayer: if an audio session ID is provided when creating an audio effect,
     * this effect will be applied only to the audio content of media players within the same
     * audio session and not to the output mix.
     * When created, a MediaPlayer instance automatically generates its own audio session ID.
     * However, it is possible to force this player to be part of an already existing audio session
     * by calling this method.
     * This method must be called before one of the overloaded <code> setDataSource </code> methods.
     * @throws IllegalStateException if it is called in an invalid state
     */
    public void setAudioSessionId(int sessionId)
            throws IllegalArgumentException, IllegalStateException {
        native_setAudioSessionId(sessionId);
        baseUpdateSessionId(sessionId);
    }

    private native void native_setAudioSessionId(int sessionId);

    /**
     * Returns the audio session ID.
     *
     * @return the audio session ID. {@see #setAudioSessionId(int)}
     * Note that the audio session ID is 0 only if a problem occured when the MediaPlayer was contructed.
     */
    public native int getAudioSessionId();

    /**
     * Attaches an auxiliary effect to the player. A typical auxiliary effect is a reverberation
     * effect which can be applied on any sound source that directs a certain amount of its
     * energy to this effect. This amount is defined by setAuxEffectSendLevel().
     * See {@link #setAuxEffectSendLevel(float)}.
     * <p>After creating an auxiliary effect (e.g.
     * {@link android.media.audiofx.EnvironmentalReverb}), retrieve its ID with
     * {@link android.media.audiofx.AudioEffect#getId()} and use it when calling this method
     * to attach the player to the effect.
     * <p>To detach the effect from the player, call this method with a null effect id.
     * <p>This method must be called after one of the overloaded <code> setDataSource </code>
     * methods.
     * @param effectId system wide unique id of the effect to attach
     */
    public native void attachAuxEffect(int effectId);


    /**
     * Sets the send level of the player to the attached auxiliary effect.
     * See {@link #attachAuxEffect(int)}. The level value range is 0 to 1.0.
     * <p>By default the send level is 0, so even if an effect is attached to the player
     * this method must be called for the effect to be applied.
     * <p>Note that the passed level value is a raw scalar. UI controls should be scaled
     * logarithmically: the gain applied by audio framework ranges from -72dB to 0dB,
     * so an appropriate conversion from linear UI input x to level is:
     * x == 0 -> level = 0
     * 0 < x <= R -> level = 10^(72*(x-R)/20/R)
     * @param level send level scalar
     */
    public void setAuxEffectSendLevel(float level) {
        baseSetAuxEffectSendLevel(level);
    }

    @Override
    int playerSetAuxEffectSendLevel(boolean muting, float level) {
        _setAuxEffectSendLevel(muting ? 0.0f : level);
        return AudioSystem.SUCCESS;
    }

    private native void _setAuxEffectSendLevel(float level);

    /*
     * @param request Parcel destinated to the media player. The
     *                Interface token must be set to the IMediaPlayer
     *                one to be routed correctly through the system.
     * @param reply[out] Parcel that will contain the reply.
     * @return The status code.
     */
    private native final int native_invoke(Parcel request, Parcel reply);


    /*
     * @param update_only If true fetch only the set of metadata that have
     *                    changed since the last invocation of getMetadata.
     *                    The set is built using the unfiltered
     *                    notifications the native player sent to the
     *                    MediaPlayerService during that period of
     *                    time. If false, all the metadatas are considered.
     * @param apply_filter  If true, once the metadata set has been built based on
     *                     the value update_only, the current filter is applied.
     * @param reply[out] On return contains the serialized
     *                   metadata. Valid only if the call was successful.
     * @return The status code.
     */
    private native final boolean native_getMetadata(boolean update_only,
                                                    boolean apply_filter,
                                                    Parcel reply);

    /*
     * @param request Parcel with the 2 serialized lists of allowed
     *                metadata types followed by the one to be
     *                dropped. Each list starts with an integer
     *                indicating the number of metadata type elements.
     * @return The status code.
     */
    private native final int native_setMetadataFilter(Parcel request);

    private static native final void native_init();
    private native void native_setup(Object mediaplayerThis,
            @NonNull Parcel attributionSource);
    private native final void native_finalize();

    /**
     * Class for MediaPlayer to return each audio/video/subtitle track's metadata.
     *
     * @see android.media.MediaPlayer#getTrackInfo
     */
    static public class TrackInfo implements Parcelable {
        /**
         * Gets the track type.
         * @return TrackType which indicates if the track is video, audio, timed text.
         */
        public @TrackType int getTrackType() {
            return mTrackType;
        }

        /**
         * Gets the language code of the track.
         * @return a language code in either way of ISO-639-1 or ISO-639-2.
         * When the language is unknown or could not be determined,
         * ISO-639-2 language code, "und", is returned.
         */
        public String getLanguage() {
            String language = mFormat.getString(MediaFormat.KEY_LANGUAGE);
            return language == null ? "und" : language;
        }

        /**
         * Gets the {@link MediaFormat} of the track.  If the format is
         * unknown or could not be determined, null is returned.
         */
        public MediaFormat getFormat() {
            if (mTrackType == MEDIA_TRACK_TYPE_TIMEDTEXT
                    || mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                return mFormat;
            }
            return null;
        }

        public static final int MEDIA_TRACK_TYPE_UNKNOWN = 0;
        public static final int MEDIA_TRACK_TYPE_VIDEO = 1;
        public static final int MEDIA_TRACK_TYPE_AUDIO = 2;
        public static final int MEDIA_TRACK_TYPE_TIMEDTEXT = 3;
        public static final int MEDIA_TRACK_TYPE_SUBTITLE = 4;
        public static final int MEDIA_TRACK_TYPE_METADATA = 5;

        /** @hide */
        @IntDef(flag = false, prefix = "MEDIA_TRACK_TYPE", value = {
                MEDIA_TRACK_TYPE_UNKNOWN,
                MEDIA_TRACK_TYPE_VIDEO,
                MEDIA_TRACK_TYPE_AUDIO,
                MEDIA_TRACK_TYPE_TIMEDTEXT,
                MEDIA_TRACK_TYPE_SUBTITLE,
                MEDIA_TRACK_TYPE_METADATA }
        )
        @Retention(RetentionPolicy.SOURCE)
        public @interface TrackType {}


        final int mTrackType;
        final MediaFormat mFormat;

        TrackInfo(Parcel in) {
            mTrackType = in.readInt();
            // TODO: parcel in the full MediaFormat; currently we are using createSubtitleFormat
            // even for audio/video tracks, meaning we only set the mime and language.
            String mime = in.readString();
            String language = in.readString();
            mFormat = MediaFormat.createSubtitleFormat(mime, language);

            if (mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                mFormat.setInteger(MediaFormat.KEY_IS_AUTOSELECT, in.readInt());
                mFormat.setInteger(MediaFormat.KEY_IS_DEFAULT, in.readInt());
                mFormat.setInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE, in.readInt());
            }
        }

        /** @hide */
        TrackInfo(int type, MediaFormat format) {
            mTrackType = type;
            mFormat = format;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mTrackType);
            dest.writeString(mFormat.getString(MediaFormat.KEY_MIME));
            dest.writeString(getLanguage());

            if (mTrackType == MEDIA_TRACK_TYPE_SUBTITLE) {
                dest.writeInt(mFormat.getInteger(MediaFormat.KEY_IS_AUTOSELECT));
                dest.writeInt(mFormat.getInteger(MediaFormat.KEY_IS_DEFAULT));
                dest.writeInt(mFormat.getInteger(MediaFormat.KEY_IS_FORCED_SUBTITLE));
            }
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(128);
            out.append(getClass().getName());
            out.append('{');
            switch (mTrackType) {
            case MEDIA_TRACK_TYPE_VIDEO:
                out.append("VIDEO");
                break;
            case MEDIA_TRACK_TYPE_AUDIO:
                out.append("AUDIO");
                break;
            case MEDIA_TRACK_TYPE_TIMEDTEXT:
                out.append("TIMEDTEXT");
                break;
            case MEDIA_TRACK_TYPE_SUBTITLE:
                out.append("SUBTITLE");
                break;
            default:
                out.append("UNKNOWN");
                break;
            }
            out.append(", " + mFormat.toString());
            out.append("}");
            return out.toString();
        }

        /**
         * Used to read a TrackInfo from a Parcel.
         */
        @UnsupportedAppUsage
        static final @android.annotation.NonNull Parcelable.Creator<TrackInfo> CREATOR
                = new Parcelable.Creator<TrackInfo>() {
                    @Override
                    public TrackInfo createFromParcel(Parcel in) {
                        return new TrackInfo(in);
                    }

                    @Override
                    public TrackInfo[] newArray(int size) {
                        return new TrackInfo[size];
                    }
                };

    };

    // We would like domain specific classes with more informative names than the `first` and `second`
    // in generic Pair, but we would also like to avoid creating new/trivial classes. As a compromise
    // we document the meanings of `first` and `second` here:
    //
    // Pair.first - inband track index; non-null iff representing an inband track.
    // Pair.second - a SubtitleTrack registered with mSubtitleController; non-null iff representing
    //               an inband subtitle track or any out-of-band track (subtitle or timedtext).
    private Vector<Pair<Integer, SubtitleTrack>> mIndexTrackPairs = new Vector<>();
    private BitSet mInbandTrackIndices = new BitSet();

    /**
     * Returns an array of track information.
     *
     * @return Array of track info. The total number of tracks is the array length.
     * Must be called again if an external timed text source has been added after any of the
     * addTimedTextSource methods are called.
     * @throws IllegalStateException if it is called in an invalid state.
     */
    public TrackInfo[] getTrackInfo() throws IllegalStateException {
        TrackInfo trackInfo[] = getInbandTrackInfo();
        // add out-of-band tracks
        synchronized (mIndexTrackPairs) {
            TrackInfo allTrackInfo[] = new TrackInfo[mIndexTrackPairs.size()];
            for (int i = 0; i < allTrackInfo.length; i++) {
                Pair<Integer, SubtitleTrack> p = mIndexTrackPairs.get(i);
                if (p.first != null) {
                    // inband track
                    allTrackInfo[i] = trackInfo[p.first];
                } else {
                    SubtitleTrack track = p.second;
                    allTrackInfo[i] = new TrackInfo(track.getTrackType(), track.getFormat());
                }
            }
            return allTrackInfo;
        }
    }

    private TrackInfo[] getInbandTrackInfo() throws IllegalStateException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(IMEDIA_PLAYER);
            request.writeInt(INVOKE_ID_GET_TRACK_INFO);
            invoke(request, reply);
            TrackInfo trackInfo[] = reply.createTypedArray(TrackInfo.CREATOR);
            return trackInfo;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    /* Do not change these values without updating their counterparts
     * in include/media/stagefright/MediaDefs.h and media/libstagefright/MediaDefs.cpp!
     */
    /**
     * MIME type for SubRip (SRT) container. Used in addTimedTextSource APIs.
     * @deprecated use {@link MediaFormat#MIMETYPE_TEXT_SUBRIP}
     */
    public static final String MEDIA_MIMETYPE_TEXT_SUBRIP = MediaFormat.MIMETYPE_TEXT_SUBRIP;

    /**
     * MIME type for WebVTT subtitle data.
     * @hide
     * @deprecated
     */
    public static final String MEDIA_MIMETYPE_TEXT_VTT = MediaFormat.MIMETYPE_TEXT_VTT;

    /**
     * MIME type for CEA-608 closed caption data.
     * @hide
     * @deprecated
     */
    public static final String MEDIA_MIMETYPE_TEXT_CEA_608 = MediaFormat.MIMETYPE_TEXT_CEA_608;

    /**
     * MIME type for CEA-708 closed caption data.
     * @hide
     * @deprecated
     */
    public static final String MEDIA_MIMETYPE_TEXT_CEA_708 = MediaFormat.MIMETYPE_TEXT_CEA_708;

    /*
     * A helper function to check if the mime type is supported by media framework.
     */
    private static boolean availableMimeTypeForExternalSource(String mimeType) {
        if (MEDIA_MIMETYPE_TEXT_SUBRIP.equals(mimeType)) {
            return true;
        }
        return false;
    }

    private SubtitleController mSubtitleController;

    /** @hide */
    @UnsupportedAppUsage
    public void setSubtitleAnchor(
            SubtitleController controller,
            SubtitleController.Anchor anchor) {
        // TODO: create SubtitleController in MediaPlayer
        mSubtitleController = controller;
        mSubtitleController.setAnchor(anchor);
    }

    /**
     * The private version of setSubtitleAnchor is used internally to set mSubtitleController if
     * necessary when clients don't provide their own SubtitleControllers using the public version
     * {@link #setSubtitleAnchor(SubtitleController, Anchor)} (e.g. {@link VideoView} provides one).
     */
    private synchronized void setSubtitleAnchor() {
        if ((mSubtitleController == null) && (ActivityThread.currentApplication() != null)) {
            final TimeProvider timeProvider = (TimeProvider) getMediaTimeProvider();
            final HandlerThread thread = new HandlerThread("SetSubtitleAnchorThread");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Context context = ActivityThread.currentApplication();
                    mSubtitleController =
                            new SubtitleController(context, timeProvider, MediaPlayer.this);
                    mSubtitleController.setAnchor(new Anchor() {
                        @Override
                        public void setSubtitleWidget(RenderingWidget subtitleWidget) {
                        }

                        @Override
                        public Looper getSubtitleLooper() {
                            return timeProvider.mEventHandler.getLooper();
                        }
                    });
                    thread.getLooper().quitSafely();
                }
            });
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "failed to join SetSubtitleAnchorThread");
            }
        }
    }

    private int mSelectedSubtitleTrackIndex = -1;
    private Vector<InputStream> mOpenSubtitleSources;

    private final OnSubtitleDataListener mIntSubtitleDataListener = new OnSubtitleDataListener() {
        @Override
        public void onSubtitleData(MediaPlayer mp, SubtitleData data) {
            int index = data.getTrackIndex();
            synchronized (mIndexTrackPairs) {
                for (Pair<Integer, SubtitleTrack> p : mIndexTrackPairs) {
                    if (p.first != null && p.first == index && p.second != null) {
                        // inband subtitle track that owns data
                        SubtitleTrack track = p.second;
                        track.onData(data);
                    }
                }
            }
        }
    };

    /** @hide */
    @Override
    public void onSubtitleTrackSelected(SubtitleTrack track) {
        if (mSelectedSubtitleTrackIndex >= 0) {
            try {
                selectOrDeselectInbandTrack(mSelectedSubtitleTrackIndex, false);
            } catch (IllegalStateException e) {
            }
            mSelectedSubtitleTrackIndex = -1;
        }
        synchronized (this) {
            mSubtitleDataListenerDisabled = true;
        }
        if (track == null) {
            return;
        }

        synchronized (mIndexTrackPairs) {
            for (Pair<Integer, SubtitleTrack> p : mIndexTrackPairs) {
                if (p.first != null && p.second == track) {
                    // inband subtitle track that is selected
                    mSelectedSubtitleTrackIndex = p.first;
                    break;
                }
            }
        }

        if (mSelectedSubtitleTrackIndex >= 0) {
            try {
                selectOrDeselectInbandTrack(mSelectedSubtitleTrackIndex, true);
            } catch (IllegalStateException e) {
            }
            synchronized (this) {
                mSubtitleDataListenerDisabled = false;
            }
        }
        // no need to select out-of-band tracks
    }

    /** @hide */
    @UnsupportedAppUsage
    public void addSubtitleSource(InputStream is, MediaFormat format)
            throws IllegalStateException
    {
        final InputStream fIs = is;
        final MediaFormat fFormat = format;

        if (is != null) {
            // Ensure all input streams are closed.  It is also a handy
            // way to implement timeouts in the future.
            synchronized(mOpenSubtitleSources) {
                mOpenSubtitleSources.add(is);
            }
        } else {
            Log.w(TAG, "addSubtitleSource called with null InputStream");
        }

        getMediaTimeProvider();

        // process each subtitle in its own thread
        final HandlerThread thread = new HandlerThread("SubtitleReadThread",
              Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            private int addTrack() {
                if (fIs == null || mSubtitleController == null) {
                    return MEDIA_INFO_UNSUPPORTED_SUBTITLE;
                }

                SubtitleTrack track = mSubtitleController.addTrack(fFormat);
                if (track == null) {
                    return MEDIA_INFO_UNSUPPORTED_SUBTITLE;
                }

                // TODO: do the conversion in the subtitle track
                Scanner scanner = new Scanner(fIs, "UTF-8");
                String contents = scanner.useDelimiter("\\A").next();
                synchronized(mOpenSubtitleSources) {
                    mOpenSubtitleSources.remove(fIs);
                }
                scanner.close();
                synchronized (mIndexTrackPairs) {
                    mIndexTrackPairs.add(Pair.<Integer, SubtitleTrack>create(null, track));
                }
                synchronized (mTimeProviderLock) {
                    if (mTimeProvider != null) {
                        Handler h = mTimeProvider.mEventHandler;
                        int what = TimeProvider.NOTIFY;
                        int arg1 = TimeProvider.NOTIFY_TRACK_DATA;
                        Pair<SubtitleTrack, byte[]> trackData =
                                Pair.create(track, contents.getBytes());
                        Message m = h.obtainMessage(what, arg1, 0, trackData);
                        h.sendMessage(m);
                    }
                }
                return MEDIA_INFO_EXTERNAL_METADATA_UPDATE;
            }

            public void run() {
                int res = addTrack();
                if (mEventHandler != null) {
                    Message m = mEventHandler.obtainMessage(MEDIA_INFO, res, 0, null);
                    mEventHandler.sendMessage(m);
                }
                thread.getLooper().quitSafely();
            }
        });
    }

    private void scanInternalSubtitleTracks() {
        setSubtitleAnchor();

        populateInbandTracks();

        if (mSubtitleController != null) {
            mSubtitleController.selectDefaultTrack();
        }
    }

    private void populateInbandTracks() {
        TrackInfo[] tracks = getInbandTrackInfo();
        synchronized (mIndexTrackPairs) {
            for (int i = 0; i < tracks.length; i++) {
                if (mInbandTrackIndices.get(i)) {
                    continue;
                } else {
                    mInbandTrackIndices.set(i);
                }

                if (tracks[i] == null) {
                    Log.w(TAG, "unexpected NULL track at index " + i);
                }
                // newly appeared inband track
                if (tracks[i] != null
                        && tracks[i].getTrackType() == TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) {
                    SubtitleTrack track = mSubtitleController.addTrack(
                            tracks[i].getFormat());
                    mIndexTrackPairs.add(Pair.create(i, track));
                } else {
                    mIndexTrackPairs.add(Pair.<Integer, SubtitleTrack>create(i, null));
                }
            }
        }
    }

    /* TODO: Limit the total number of external timed text source to a reasonable number.
     */
    /**
     * Adds an external timed text source file.
     *
     * Currently supported format is SubRip with the file extension .srt, case insensitive.
     * Note that a single external timed text source may contain multiple tracks in it.
     * One can find the total number of available tracks using {@link #getTrackInfo()} to see what
     * additional tracks become available after this method call.
     *
     * @param path The file path of external timed text source file.
     * @param mimeType The mime type of the file. Must be one of the mime types listed above.
     * @throws IOException if the file cannot be accessed or is corrupted.
     * @throws IllegalArgumentException if the mimeType is not supported.
     * @throws IllegalStateException if called in an invalid state.
     */
    public void addTimedTextSource(String path, String mimeType)
            throws IOException, IllegalArgumentException, IllegalStateException {
        if (!availableMimeTypeForExternalSource(mimeType)) {
            final String msg = "Illegal mimeType for timed text source: " + mimeType;
            throw new IllegalArgumentException(msg);
        }

        final File file = new File(path);
        try (FileInputStream is = new FileInputStream(file)) {
            addTimedTextSource(is.getFD(), mimeType);
        }
    }

    /**
     * Adds an external timed text source file (Uri).
     *
     * Currently supported format is SubRip with the file extension .srt, case insensitive.
     * Note that a single external timed text source may contain multiple tracks in it.
     * One can find the total number of available tracks using {@link #getTrackInfo()} to see what
     * additional tracks become available after this method call.
     *
     * @param context the Context to use when resolving the Uri
     * @param uri the Content URI of the data you want to play
     * @param mimeType The mime type of the file. Must be one of the mime types listed above.
     * @throws IOException if the file cannot be accessed or is corrupted.
     * @throws IllegalArgumentException if the mimeType is not supported.
     * @throws IllegalStateException if called in an invalid state.
     */
    public void addTimedTextSource(Context context, Uri uri, String mimeType)
            throws IOException, IllegalArgumentException, IllegalStateException {
        String scheme = uri.getScheme();
        if(scheme == null || scheme.equals("file")) {
            addTimedTextSource(uri.getPath(), mimeType);
            return;
        }

        AssetFileDescriptor fd = null;
        try {
            boolean optimize = SystemProperties.getBoolean("fuse.sys.transcode_player_optimize",
                    false);
            ContentResolver resolver = context.getContentResolver();
            Bundle opts = new Bundle();
            opts.putBoolean("android.provider.extra.ACCEPT_ORIGINAL_MEDIA_FORMAT", true);
            fd = optimize ? resolver.openTypedAssetFileDescriptor(uri, "*/*", opts)
                    : resolver.openAssetFileDescriptor(uri, "r");
            if (fd == null) {
                return;
            }
            addTimedTextSource(fd.getFileDescriptor(), mimeType);
            return;
        } catch (SecurityException ex) {
        } catch (IOException ex) {
        } finally {
            if (fd != null) {
                fd.close();
            }
        }
    }

    /**
     * Adds an external timed text source file (FileDescriptor).
     *
     * It is the caller's responsibility to close the file descriptor.
     * It is safe to do so as soon as this call returns.
     *
     * Currently supported format is SubRip. Note that a single external timed text source may
     * contain multiple tracks in it. One can find the total number of available tracks
     * using {@link #getTrackInfo()} to see what additional tracks become available
     * after this method call.
     *
     * @param fd the FileDescriptor for the file you want to play
     * @param mimeType The mime type of the file. Must be one of the mime types listed above.
     * @throws IllegalArgumentException if the mimeType is not supported.
     * @throws IllegalStateException if called in an invalid state.
     */
    public void addTimedTextSource(FileDescriptor fd, String mimeType)
            throws IllegalArgumentException, IllegalStateException {
        // intentionally less than LONG_MAX
        addTimedTextSource(fd, 0, 0x7ffffffffffffffL, mimeType);
    }

    /**
     * Adds an external timed text file (FileDescriptor).
     *
     * It is the caller's responsibility to close the file descriptor.
     * It is safe to do so as soon as this call returns.
     *
     * Currently supported format is SubRip. Note that a single external timed text source may
     * contain multiple tracks in it. One can find the total number of available tracks
     * using {@link #getTrackInfo()} to see what additional tracks become available
     * after this method call.
     *
     * @param fd the FileDescriptor for the file you want to play
     * @param offset the offset into the file where the data to be played starts, in bytes
     * @param length the length in bytes of the data to be played
     * @param mime The mime type of the file. Must be one of the mime types listed above.
     * @throws IllegalArgumentException if the mimeType is not supported.
     * @throws IllegalStateException if called in an invalid state.
     */
    public void addTimedTextSource(FileDescriptor fd, long offset, long length, String mime)
            throws IllegalArgumentException, IllegalStateException {
        if (!availableMimeTypeForExternalSource(mime)) {
            throw new IllegalArgumentException("Illegal mimeType for timed text source: " + mime);
        }

        final FileDescriptor dupedFd;
        try {
            dupedFd = Os.dup(fd);
        } catch (ErrnoException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }

        final MediaFormat fFormat = new MediaFormat();
        fFormat.setString(MediaFormat.KEY_MIME, mime);
        fFormat.setInteger(MediaFormat.KEY_IS_TIMED_TEXT, 1);

        // A MediaPlayer created by a VideoView should already have its mSubtitleController set.
        if (mSubtitleController == null) {
            setSubtitleAnchor();
        }

        if (!mSubtitleController.hasRendererFor(fFormat)) {
            // test and add not atomic
            Context context = ActivityThread.currentApplication();
            mSubtitleController.registerRenderer(new SRTRenderer(context, mEventHandler));
        }
        final SubtitleTrack track = mSubtitleController.addTrack(fFormat);
        synchronized (mIndexTrackPairs) {
            mIndexTrackPairs.add(Pair.<Integer, SubtitleTrack>create(null, track));
        }

        getMediaTimeProvider();

        final long offset2 = offset;
        final long length2 = length;
        final HandlerThread thread = new HandlerThread(
                "TimedTextReadThread",
                Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            private int addTrack() {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    Os.lseek(dupedFd, offset2, OsConstants.SEEK_SET);
                    byte[] buffer = new byte[4096];
                    for (long total = 0; total < length2;) {
                        int bytesToRead = (int) Math.min(buffer.length, length2 - total);
                        int bytes = IoBridge.read(dupedFd, buffer, 0, bytesToRead);
                        if (bytes < 0) {
                            break;
                        } else {
                            bos.write(buffer, 0, bytes);
                            total += bytes;
                        }
                    }
                    synchronized (mTimeProviderLock) {
                        if (mTimeProvider != null) {
                            Handler h = mTimeProvider.mEventHandler;
                            int what = TimeProvider.NOTIFY;
                            int arg1 = TimeProvider.NOTIFY_TRACK_DATA;
                            Pair<SubtitleTrack, byte[]> trackData =
                                    Pair.create(track, bos.toByteArray());
                            Message m = h.obtainMessage(what, arg1, 0, trackData);
                            h.sendMessage(m);
                        }
                    }
                    return MEDIA_INFO_EXTERNAL_METADATA_UPDATE;
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage(), e);
                    return MEDIA_INFO_TIMED_TEXT_ERROR;
                } finally {
                    try {
                        Os.close(dupedFd);
                    } catch (ErrnoException e) {
                        Log.e(TAG, e.getMessage(), e);
                    }
                }
            }

            public void run() {
                int res = addTrack();
                if (mEventHandler != null) {
                    Message m = mEventHandler.obtainMessage(MEDIA_INFO, res, 0, null);
                    mEventHandler.sendMessage(m);
                }
                thread.getLooper().quitSafely();
            }
        });
    }

    /**
     * Returns the index of the audio, video, or subtitle track currently selected for playback,
     * The return value is an index into the array returned by {@link #getTrackInfo()}, and can
     * be used in calls to {@link #selectTrack(int)} or {@link #deselectTrack(int)}.
     *
     * @param trackType should be one of {@link TrackInfo#MEDIA_TRACK_TYPE_VIDEO},
     * {@link TrackInfo#MEDIA_TRACK_TYPE_AUDIO}, or
     * {@link TrackInfo#MEDIA_TRACK_TYPE_SUBTITLE}
     * @return index of the audio, video, or subtitle track currently selected for playback;
     * a negative integer is returned when there is no selected track for {@code trackType} or
     * when {@code trackType} is not one of audio, video, or subtitle.
     * @throws IllegalStateException if called after {@link #release()}
     *
     * @see #getTrackInfo()
     * @see #selectTrack(int)
     * @see #deselectTrack(int)
     */
    public int getSelectedTrack(int trackType) throws IllegalStateException {
        if (mSubtitleController != null
                && (trackType == TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                || trackType == TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)) {
            SubtitleTrack subtitleTrack = mSubtitleController.getSelectedTrack();
            if (subtitleTrack != null) {
                synchronized (mIndexTrackPairs) {
                    for (int i = 0; i < mIndexTrackPairs.size(); i++) {
                        Pair<Integer, SubtitleTrack> p = mIndexTrackPairs.get(i);
                        if (p.second == subtitleTrack && subtitleTrack.getTrackType() == trackType) {
                            return i;
                        }
                    }
                }
            }
        }

        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(IMEDIA_PLAYER);
            request.writeInt(INVOKE_ID_GET_SELECTED_TRACK);
            request.writeInt(trackType);
            invoke(request, reply);
            int inbandTrackIndex = reply.readInt();
            synchronized (mIndexTrackPairs) {
                for (int i = 0; i < mIndexTrackPairs.size(); i++) {
                    Pair<Integer, SubtitleTrack> p = mIndexTrackPairs.get(i);
                    if (p.first != null && p.first == inbandTrackIndex) {
                        return i;
                    }
                }
            }
            return -1;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    /**
     * Selects a track.
     * <p>
     * If a MediaPlayer is in invalid state, it throws an IllegalStateException exception.
     * If a MediaPlayer is in <em>Started</em> state, the selected track is presented immediately.
     * If a MediaPlayer is not in Started state, it just marks the track to be played.
     * </p>
     * <p>
     * In any valid state, if it is called multiple times on the same type of track (ie. Video,
     * Audio, Timed Text), the most recent one will be chosen.
     * </p>
     * <p>
     * The first audio and video tracks are selected by default if available, even though
     * this method is not called. However, no timed text track will be selected until
     * this function is called.
     * </p>
     * <p>
     * Currently, only timed text, subtitle or audio tracks can be selected via this method.
     * In addition, the support for selecting an audio track at runtime is pretty limited
     * in that an audio track can only be selected in the <em>Prepared</em> state.
     * </p>
     * @param index the index of the track to be selected. The valid range of the index
     * is 0..total number of track - 1. The total number of tracks as well as the type of
     * each individual track can be found by calling {@link #getTrackInfo()} method.
     * @throws IllegalStateException if called in an invalid state.
     *
     * @see android.media.MediaPlayer#getTrackInfo
     */
    public void selectTrack(int index) throws IllegalStateException {
        selectOrDeselectTrack(index, true /* select */);
    }

    /**
     * Deselect a track.
     * <p>
     * Currently, the track must be a timed text track and no audio or video tracks can be
     * deselected. If the timed text track identified by index has not been
     * selected before, it throws an exception.
     * </p>
     * @param index the index of the track to be deselected. The valid range of the index
     * is 0..total number of tracks - 1. The total number of tracks as well as the type of
     * each individual track can be found by calling {@link #getTrackInfo()} method.
     * @throws IllegalStateException if called in an invalid state.
     *
     * @see android.media.MediaPlayer#getTrackInfo
     */
    public void deselectTrack(int index) throws IllegalStateException {
        selectOrDeselectTrack(index, false /* select */);
    }

    private void selectOrDeselectTrack(int index, boolean select)
            throws IllegalStateException {
        // handle subtitle track through subtitle controller
        populateInbandTracks();

        Pair<Integer,SubtitleTrack> p = null;
        try {
            p = mIndexTrackPairs.get(index);
        } catch (ArrayIndexOutOfBoundsException e) {
            // ignore bad index
            return;
        }

        SubtitleTrack track = p.second;
        if (track == null) {
            // inband (de)select
            selectOrDeselectInbandTrack(p.first, select);
            return;
        }

        if (mSubtitleController == null) {
            return;
        }

        if (!select) {
            // out-of-band deselect
            if (mSubtitleController.getSelectedTrack() == track) {
                mSubtitleController.selectTrack(null);
            } else {
                Log.w(TAG, "trying to deselect track that was not selected");
            }
            return;
        }

        // out-of-band select
        if (track.getTrackType() == TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
            int ttIndex = getSelectedTrack(TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            synchronized (mIndexTrackPairs) {
                if (ttIndex >= 0 && ttIndex < mIndexTrackPairs.size()) {
                    Pair<Integer,SubtitleTrack> p2 = mIndexTrackPairs.get(ttIndex);
                    if (p2.first != null && p2.second == null) {
                        // deselect inband counterpart
                        selectOrDeselectInbandTrack(p2.first, false);
                    }
                }
            }
        }
        mSubtitleController.selectTrack(track);
    }

    private void selectOrDeselectInbandTrack(int index, boolean select)
            throws IllegalStateException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(IMEDIA_PLAYER);
            request.writeInt(select? INVOKE_ID_SELECT_TRACK: INVOKE_ID_DESELECT_TRACK);
            request.writeInt(index);
            invoke(request, reply);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }


    /**
     * @param reply Parcel with audio/video duration info for battery
                    tracking usage
     * @return The status code.
     * {@hide}
     */
    public native static int native_pullBatteryData(Parcel reply);

    /**
     * Sets the target UDP re-transmit endpoint for the low level player.
     * Generally, the address portion of the endpoint is an IP multicast
     * address, although a unicast address would be equally valid.  When a valid
     * retransmit endpoint has been set, the media player will not decode and
     * render the media presentation locally.  Instead, the player will attempt
     * to re-multiplex its media data using the Android@Home RTP profile and
     * re-transmit to the target endpoint.  Receiver devices (which may be
     * either the same as the transmitting device or different devices) may
     * instantiate, prepare, and start a receiver player using a setDataSource
     * URL of the form...
     *
     * aahRX://&lt;multicastIP&gt;:&lt;port&gt;
     *
     * to receive, decode and render the re-transmitted content.
     *
     * setRetransmitEndpoint may only be called before setDataSource has been
     * called; while the player is in the Idle state.
     *
     * @param endpoint the address and UDP port of the re-transmission target or
     * null if no re-transmission is to be performed.
     * @throws IllegalStateException if it is called in an invalid state
     * @throws IllegalArgumentException if the retransmit endpoint is supplied,
     * but invalid.
     *
     * {@hide} pending API council
     */
    @UnsupportedAppUsage
    public void setRetransmitEndpoint(InetSocketAddress endpoint)
            throws IllegalStateException, IllegalArgumentException
    {
        String addrString = null;
        int port = 0;

        if (null != endpoint) {
            addrString = endpoint.getAddress().getHostAddress();
            port = endpoint.getPort();
        }

        int ret = native_setRetransmitEndpoint(addrString, port);
        if (ret != 0) {
            throw new IllegalArgumentException("Illegal re-transmit endpoint; native ret " + ret);
        }
    }

    private native final int native_setRetransmitEndpoint(String addrString, int port);

    @Override
    protected void finalize() {
        tryToDisableNativeRoutingCallback();
        baseRelease();
        native_finalize();
    }

    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer.h!
     */
    private static final int MEDIA_NOP = 0; // interface test message
    private static final int MEDIA_PREPARED = 1;
    private static final int MEDIA_PLAYBACK_COMPLETE = 2;
    private static final int MEDIA_BUFFERING_UPDATE = 3;
    private static final int MEDIA_SEEK_COMPLETE = 4;
    private static final int MEDIA_SET_VIDEO_SIZE = 5;
    private static final int MEDIA_STARTED = 6;
    private static final int MEDIA_PAUSED = 7;
    private static final int MEDIA_STOPPED = 8;
    private static final int MEDIA_SKIPPED = 9;
    private static final int MEDIA_NOTIFY_TIME = 98;
    private static final int MEDIA_TIMED_TEXT = 99;
    private static final int MEDIA_ERROR = 100;
    private static final int MEDIA_INFO = 200;
    private static final int MEDIA_SUBTITLE_DATA = 201;
    private static final int MEDIA_META_DATA = 202;
    private static final int MEDIA_DRM_INFO = 210;
    private static final int MEDIA_TIME_DISCONTINUITY = 211;
    private static final int MEDIA_RTP_RX_NOTICE = 300;
    private static final int MEDIA_AUDIO_ROUTING_CHANGED = 10000;

    private TimeProvider mTimeProvider;
    private final Object mTimeProviderLock = new Object();

    /** @hide */
    @UnsupportedAppUsage
    public MediaTimeProvider getMediaTimeProvider() {
        synchronized (mTimeProviderLock) {
            if (mTimeProvider == null) {
                mTimeProvider = new TimeProvider(this);
            }
            return mTimeProvider;
        }
    }

    private class EventHandler extends Handler
    {
        private MediaPlayer mMediaPlayer;

        public EventHandler(MediaPlayer mp, Looper looper) {
            super(looper);
            mMediaPlayer = mp;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mMediaPlayer.mNativeContext == 0) {
                Log.w(TAG, "mediaplayer went away with unhandled events");
                return;
            }
            switch(msg.what) {
            case MEDIA_PREPARED:
                try {
                    scanInternalSubtitleTracks();
                } catch (RuntimeException e) {
                    // send error message instead of crashing;
                    // send error message instead of inlining a call to onError
                    // to avoid code duplication.
                    Message msg2 = obtainMessage(
                            MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_UNSUPPORTED, null);
                    sendMessage(msg2);
                }

                OnPreparedListener onPreparedListener = mOnPreparedListener;
                if (onPreparedListener != null)
                    onPreparedListener.onPrepared(mMediaPlayer);
                return;

            case MEDIA_DRM_INFO:
                Log.v(TAG, "MEDIA_DRM_INFO " + mOnDrmInfoHandlerDelegate);

                if (msg.obj == null) {
                    Log.w(TAG, "MEDIA_DRM_INFO msg.obj=NULL");
                } else if (msg.obj instanceof Parcel) {
                    // The parcel was parsed already in postEventFromNative
                    DrmInfo drmInfo = null;

                    OnDrmInfoHandlerDelegate onDrmInfoHandlerDelegate;
                    synchronized (mDrmLock) {
                        if (mOnDrmInfoHandlerDelegate != null && mDrmInfo != null) {
                            drmInfo = mDrmInfo.makeCopy();
                        }
                        // local copy while keeping the lock
                        onDrmInfoHandlerDelegate = mOnDrmInfoHandlerDelegate;
                    }

                    // notifying the client outside the lock
                    if (onDrmInfoHandlerDelegate != null) {
                        onDrmInfoHandlerDelegate.notifyClient(drmInfo);
                    }
                } else {
                    Log.w(TAG, "MEDIA_DRM_INFO msg.obj of unexpected type " + msg.obj);
                }
                return;

            case MEDIA_PLAYBACK_COMPLETE:
                {
                    mOnCompletionInternalListener.onCompletion(mMediaPlayer);
                    OnCompletionListener onCompletionListener = mOnCompletionListener;
                    if (onCompletionListener != null)
                        onCompletionListener.onCompletion(mMediaPlayer);
                }
                stayAwake(false);
                return;

            case MEDIA_STOPPED:
                {
                    TimeProvider timeProvider = mTimeProvider;
                    if (timeProvider != null) {
                        timeProvider.onStopped();
                    }
                }
                break;

            case MEDIA_STARTED:
                // fall through
            case MEDIA_PAUSED:
                {
                    TimeProvider timeProvider = mTimeProvider;
                    if (timeProvider != null) {
                        timeProvider.onPaused(msg.what == MEDIA_PAUSED);
                    }
                }
                break;

            case MEDIA_BUFFERING_UPDATE:
                OnBufferingUpdateListener onBufferingUpdateListener = mOnBufferingUpdateListener;
                if (onBufferingUpdateListener != null)
                    onBufferingUpdateListener.onBufferingUpdate(mMediaPlayer, msg.arg1);
                return;

            case MEDIA_SEEK_COMPLETE:
                OnSeekCompleteListener onSeekCompleteListener = mOnSeekCompleteListener;
                if (onSeekCompleteListener != null) {
                    onSeekCompleteListener.onSeekComplete(mMediaPlayer);
                }
                // fall through

            case MEDIA_SKIPPED:
                {
                    TimeProvider timeProvider = mTimeProvider;
                    if (timeProvider != null) {
                        timeProvider.onSeekComplete(mMediaPlayer);
                    }
                }
                return;

            case MEDIA_SET_VIDEO_SIZE:
                OnVideoSizeChangedListener onVideoSizeChangedListener = mOnVideoSizeChangedListener;
                if (onVideoSizeChangedListener != null) {
                    onVideoSizeChangedListener.onVideoSizeChanged(
                        mMediaPlayer, msg.arg1, msg.arg2);
                }
                return;

            case MEDIA_ERROR:
                Log.e(TAG, "Error (" + msg.arg1 + "," + msg.arg2 + ")");
                boolean error_was_handled = false;
                OnErrorListener onErrorListener = mOnErrorListener;
                if (onErrorListener != null) {
                    error_was_handled = onErrorListener.onError(mMediaPlayer, msg.arg1, msg.arg2);
                }
                {
                    mOnCompletionInternalListener.onCompletion(mMediaPlayer);
                    OnCompletionListener onCompletionListener = mOnCompletionListener;
                    if (onCompletionListener != null && ! error_was_handled) {
                        onCompletionListener.onCompletion(mMediaPlayer);
                    }
                }
                stayAwake(false);
                return;

            case MEDIA_INFO:
                switch (msg.arg1) {
                case MEDIA_INFO_VIDEO_TRACK_LAGGING:
                    Log.i(TAG, "Info (" + msg.arg1 + "," + msg.arg2 + ")");
                    break;
                case MEDIA_INFO_METADATA_UPDATE:
                    try {
                        scanInternalSubtitleTracks();
                    } catch (RuntimeException e) {
                        Message msg2 = obtainMessage(
                                MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, MEDIA_ERROR_UNSUPPORTED, null);
                        sendMessage(msg2);
                    }
                    // fall through

                case MEDIA_INFO_EXTERNAL_METADATA_UPDATE:
                    msg.arg1 = MEDIA_INFO_METADATA_UPDATE;
                    // update default track selection
                    if (mSubtitleController != null) {
                        mSubtitleController.selectDefaultTrack();
                    }
                    break;
                case MEDIA_INFO_BUFFERING_START:
                case MEDIA_INFO_BUFFERING_END:
                    TimeProvider timeProvider = mTimeProvider;
                    if (timeProvider != null) {
                        timeProvider.onBuffering(msg.arg1 == MEDIA_INFO_BUFFERING_START);
                    }
                    break;
                }

                OnInfoListener onInfoListener = mOnInfoListener;
                if (onInfoListener != null) {
                    onInfoListener.onInfo(mMediaPlayer, msg.arg1, msg.arg2);
                }
                // No real default action so far.
                return;

            case MEDIA_NOTIFY_TIME:
                    TimeProvider timeProvider = mTimeProvider;
                    if (timeProvider != null) {
                        timeProvider.onNotifyTime();
                    }
                return;

            case MEDIA_TIMED_TEXT:
                OnTimedTextListener onTimedTextListener = mOnTimedTextListener;
                if (onTimedTextListener == null)
                    return;
                if (msg.obj == null) {
                    onTimedTextListener.onTimedText(mMediaPlayer, null);
                } else {
                    if (msg.obj instanceof Parcel) {
                        Parcel parcel = (Parcel)msg.obj;
                        TimedText text = new TimedText(parcel);
                        parcel.recycle();
                        onTimedTextListener.onTimedText(mMediaPlayer, text);
                    }
                }
                return;

            case MEDIA_SUBTITLE_DATA:
                final OnSubtitleDataListener extSubtitleListener;
                final Handler extSubtitleHandler;
                synchronized(this) {
                    if (mSubtitleDataListenerDisabled) {
                        return;
                    }
                    extSubtitleListener = mExtSubtitleDataListener;
                    extSubtitleHandler = mExtSubtitleDataHandler;
                }
                if (msg.obj instanceof Parcel) {
                    Parcel parcel = (Parcel) msg.obj;
                    final SubtitleData data = new SubtitleData(parcel);
                    parcel.recycle();

                    mIntSubtitleDataListener.onSubtitleData(mMediaPlayer, data);

                    if (extSubtitleListener != null) {
                        if (extSubtitleHandler == null) {
                            extSubtitleListener.onSubtitleData(mMediaPlayer, data);
                        } else {
                            extSubtitleHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    extSubtitleListener.onSubtitleData(mMediaPlayer, data);
                                }
                            });
                        }
                    }
                }
                return;

            case MEDIA_META_DATA:
                OnTimedMetaDataAvailableListener onTimedMetaDataAvailableListener =
                    mOnTimedMetaDataAvailableListener;
                if (onTimedMetaDataAvailableListener == null) {
                    return;
                }
                if (msg.obj instanceof Parcel) {
                    Parcel parcel = (Parcel) msg.obj;
                    TimedMetaData data = TimedMetaData.createTimedMetaDataFromParcel(parcel);
                    parcel.recycle();
                    onTimedMetaDataAvailableListener.onTimedMetaDataAvailable(mMediaPlayer, data);
                }
                return;

            case MEDIA_NOP: // interface test message - ignore
                break;

            case MEDIA_AUDIO_ROUTING_CHANGED:
                    broadcastRoutingChange();
                    return;

            case MEDIA_TIME_DISCONTINUITY:
                final OnMediaTimeDiscontinuityListener mediaTimeListener;
                final Handler mediaTimeHandler;
                synchronized(this) {
                    mediaTimeListener = mOnMediaTimeDiscontinuityListener;
                    mediaTimeHandler = mOnMediaTimeDiscontinuityHandler;
                }
                if (mediaTimeListener == null) {
                    return;
                }
                if (msg.obj instanceof Parcel) {
                    Parcel parcel = (Parcel) msg.obj;
                    parcel.setDataPosition(0);
                    long anchorMediaUs = parcel.readLong();
                    long anchorRealUs = parcel.readLong();
                    float playbackRate = parcel.readFloat();
                    parcel.recycle();
                    final MediaTimestamp timestamp;
                    if (anchorMediaUs != -1 && anchorRealUs != -1) {
                        timestamp = new MediaTimestamp(
                                anchorMediaUs /*Us*/, anchorRealUs * 1000 /*Ns*/, playbackRate);
                    } else {
                        timestamp = MediaTimestamp.TIMESTAMP_UNKNOWN;
                    }
                    if (mediaTimeHandler == null) {
                        mediaTimeListener.onMediaTimeDiscontinuity(mMediaPlayer, timestamp);
                    } else {
                        mediaTimeHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mediaTimeListener.onMediaTimeDiscontinuity(mMediaPlayer, timestamp);
                            }
                        });
                    }
                }
                return;

            case MEDIA_RTP_RX_NOTICE:
                final OnRtpRxNoticeListener rtpRxNoticeListener = mOnRtpRxNoticeListener;
                if (rtpRxNoticeListener == null) {
                    return;
                }
                if (msg.obj instanceof Parcel) {
                    Parcel parcel = (Parcel) msg.obj;
                    parcel.setDataPosition(0);
                    int noticeType;
                    int[] data;
                    try {
                        noticeType = parcel.readInt();
                        int numOfArgs = parcel.dataAvail() / 4;
                        data = new int[numOfArgs];
                        for (int i = 0; i < numOfArgs; i++) {
                            data[i] = parcel.readInt();
                        }
                    } finally {
                        parcel.recycle();
                    }
                    mOnRtpRxNoticeExecutor.execute(() ->
                            rtpRxNoticeListener
                                    .onRtpRxNotice(mMediaPlayer, noticeType, data));
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /*
     * Called from native code when an interesting event happens.  This method
     * just uses the EventHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaPlayer object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(Object mediaplayer_ref,
                                            int what, int arg1, int arg2, Object obj)
    {
        final MediaPlayer mp = (MediaPlayer)((WeakReference)mediaplayer_ref).get();
        if (mp == null) {
            return;
        }

        switch (what) {
        case MEDIA_INFO:
            if (arg1 == MEDIA_INFO_STARTED_AS_NEXT) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // this acquires the wakelock if needed, and sets the client side state
                        mp.start();
                    }
                }).start();
                Thread.yield();
            }
            break;

        case MEDIA_DRM_INFO:
            // We need to derive mDrmInfo before prepare() returns so processing it here
            // before the notification is sent to EventHandler below. EventHandler runs in the
            // notification looper so its handleMessage might process the event after prepare()
            // has returned.
            Log.v(TAG, "postEventFromNative MEDIA_DRM_INFO");
            if (obj instanceof Parcel) {
                Parcel parcel = (Parcel)obj;
                DrmInfo drmInfo = new DrmInfo(parcel);
                synchronized (mp.mDrmLock) {
                    mp.mDrmInfo = drmInfo;
                }
            } else {
                Log.w(TAG, "MEDIA_DRM_INFO msg.obj of unexpected type " + obj);
            }
            break;

        case MEDIA_PREPARED:
            // By this time, we've learned about DrmInfo's presence or absence. This is meant
            // mainly for prepareAsync() use case. For prepare(), this still can run to a race
            // condition b/c MediaPlayerNative releases the prepare() lock before calling notify
            // so we also set mDrmInfoResolved in prepare().
            synchronized (mp.mDrmLock) {
                mp.mDrmInfoResolved = true;
            }
            break;

        }

        if (mp.mEventHandler != null) {
            Message m = mp.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            mp.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Interface definition for a callback to be invoked when the media
     * source is ready for playback.
     */
    public interface OnPreparedListener
    {
        /**
         * Called when the media file is ready for playback.
         *
         * @param mp the MediaPlayer that is ready for playback
         */
        void onPrepared(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked when the media source is ready
     * for playback.
     *
     * @param listener the callback that will be run
     */
    public void setOnPreparedListener(OnPreparedListener listener)
    {
        mOnPreparedListener = listener;
    }

    @UnsupportedAppUsage
    private OnPreparedListener mOnPreparedListener;

    /**
     * Interface definition for a callback to be invoked when playback of
     * a media source has completed.
     */
    public interface OnCompletionListener
    {
        /**
         * Called when the end of a media source is reached during playback.
         *
         * @param mp the MediaPlayer that reached the end of the file
         */
        void onCompletion(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked when the end of a media source
     * has been reached during playback.
     *
     * @param listener the callback that will be run
     */
    public void setOnCompletionListener(OnCompletionListener listener)
    {
        mOnCompletionListener = listener;
    }

    @UnsupportedAppUsage
    private OnCompletionListener mOnCompletionListener;

    /**
     * @hide
     * Internal completion listener to update PlayerBase of the play state. Always "registered".
     */
    private final OnCompletionListener mOnCompletionInternalListener = new OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
            tryToDisableNativeRoutingCallback();
            baseStop();
        }
    };

    /**
     * Interface definition of a callback to be invoked indicating buffering
     * status of a media resource being streamed over the network.
     */
    public interface OnBufferingUpdateListener
    {
        /**
         * Called to update status in buffering a media stream received through
         * progressive HTTP download. The received buffering percentage
         * indicates how much of the content has been buffered or played.
         * For example a buffering update of 80 percent when half the content
         * has already been played indicates that the next 30 percent of the
         * content to play has been buffered.
         *
         * @param mp      the MediaPlayer the update pertains to
         * @param percent the percentage (0-100) of the content
         *                that has been buffered or played thus far
         */
        void onBufferingUpdate(MediaPlayer mp, int percent);
    }

    /**
     * Register a callback to be invoked when the status of a network
     * stream's buffer has changed.
     *
     * @param listener the callback that will be run.
     */
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener)
    {
        mOnBufferingUpdateListener = listener;
    }

    private OnBufferingUpdateListener mOnBufferingUpdateListener;

    /**
     * Interface definition of a callback to be invoked indicating
     * the completion of a seek operation.
     */
    public interface OnSeekCompleteListener
    {
        /**
         * Called to indicate the completion of a seek operation.
         *
         * @param mp the MediaPlayer that issued the seek operation
         */
        public void onSeekComplete(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked when a seek operation has been
     * completed.
     *
     * @param listener the callback that will be run
     */
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener)
    {
        mOnSeekCompleteListener = listener;
    }

    @UnsupportedAppUsage
    private OnSeekCompleteListener mOnSeekCompleteListener;

    /**
     * Interface definition of a callback to be invoked when the
     * video size is first known or updated
     */
    public interface OnVideoSizeChangedListener
    {
        /**
         * Called to indicate the video size
         *
         * The video size (width and height) could be 0 if there was no video,
         * no display surface was set, or the value was not determined yet.
         *
         * @param mp        the MediaPlayer associated with this callback
         * @param width     the width of the video
         * @param height    the height of the video
         */
        public void onVideoSizeChanged(MediaPlayer mp, int width, int height);
    }

    /**
     * Register a callback to be invoked when the video size is
     * known or updated.
     *
     * @param listener the callback that will be run
     */
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener)
    {
        mOnVideoSizeChangedListener = listener;
    }

    private OnVideoSizeChangedListener mOnVideoSizeChangedListener;

    /**
     * Interface definition of a callback to be invoked when a
     * timed text is available for display.
     */
    public interface OnTimedTextListener
    {
        /**
         * Called to indicate an avaliable timed text
         *
         * @param mp             the MediaPlayer associated with this callback
         * @param text           the timed text sample which contains the text
         *                       needed to be displayed and the display format.
         */
        public void onTimedText(MediaPlayer mp, TimedText text);
    }

    /**
     * Register a callback to be invoked when a timed text is available
     * for display.
     *
     * @param listener the callback that will be run
     */
    public void setOnTimedTextListener(OnTimedTextListener listener)
    {
        mOnTimedTextListener = listener;
    }

    @UnsupportedAppUsage
    private OnTimedTextListener mOnTimedTextListener;

    /**
     * Interface definition of a callback to be invoked when a player subtitle track has new
     * subtitle data available.
     * See the {@link MediaPlayer#setOnSubtitleDataListener(OnSubtitleDataListener, Handler)}
     * method for the description of which track will report data through this listener.
     */
    public interface OnSubtitleDataListener {
        /**
         * Method called when new subtitle data is available
         * @param mp the player that reports the new subtitle data
         * @param data the subtitle data
         */
        public void onSubtitleData(@NonNull MediaPlayer mp, @NonNull SubtitleData data);
    }

    /**
     * Sets the listener to be invoked when a subtitle track has new data available.
     * The subtitle data comes from a subtitle track previously selected with
     * {@link #selectTrack(int)}. Use {@link #getTrackInfo()} to determine which tracks are
     * subtitles (of type {@link TrackInfo#MEDIA_TRACK_TYPE_SUBTITLE}), Subtitle track encodings
     * can be determined by {@link TrackInfo#getFormat()}).<br>
     * See {@link SubtitleData} for an example of querying subtitle encoding.
     * @param listener the listener called when new data is available
     * @param handler the {@link Handler} that receives the listener events
     */
    public void setOnSubtitleDataListener(@NonNull OnSubtitleDataListener listener,
            @NonNull Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("Illegal null listener");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Illegal null handler");
        }
        setOnSubtitleDataListenerInt(listener, handler);
    }
    /**
     * Sets the listener to be invoked when a subtitle track has new data available.
     * The subtitle data comes from a subtitle track previously selected with
     * {@link #selectTrack(int)}. Use {@link #getTrackInfo()} to determine which tracks are
     * subtitles (of type {@link TrackInfo#MEDIA_TRACK_TYPE_SUBTITLE}), Subtitle track encodings
     * can be determined by {@link TrackInfo#getFormat()}).<br>
     * See {@link SubtitleData} for an example of querying subtitle encoding.<br>
     * The listener will be called on the same thread as the one in which the MediaPlayer was
     * created.
     * @param listener the listener called when new data is available
     */
    public void setOnSubtitleDataListener(@NonNull OnSubtitleDataListener listener)
    {
        if (listener == null) {
            throw new IllegalArgumentException("Illegal null listener");
        }
        setOnSubtitleDataListenerInt(listener, null);
    }

    /**
     * Clears the listener previously set with
     * {@link #setOnSubtitleDataListener(OnSubtitleDataListener)} or
     * {@link #setOnSubtitleDataListener(OnSubtitleDataListener, Handler)}.
     */
    public void clearOnSubtitleDataListener() {
        setOnSubtitleDataListenerInt(null, null);
    }

    private void setOnSubtitleDataListenerInt(
            @Nullable OnSubtitleDataListener listener, @Nullable Handler handler) {
        synchronized (this) {
            mExtSubtitleDataListener = listener;
            mExtSubtitleDataHandler = handler;
        }
    }

    private boolean mSubtitleDataListenerDisabled;
    /** External OnSubtitleDataListener, the one set by {@link #setOnSubtitleDataListener}. */
    private OnSubtitleDataListener mExtSubtitleDataListener;
    private Handler mExtSubtitleDataHandler;

    /**
     * Interface definition of a callback to be invoked when discontinuity in the normal progression
     * of the media time is detected.
     * The "normal progression" of media time is defined as the expected increase of the playback
     * position when playing media, relative to the playback speed (for instance every second, media
     * time increases by two seconds when playing at 2x).<br>
     * Discontinuities are encountered in the following cases:
     * <ul>
     * <li>when the player is starved for data and cannot play anymore</li>
     * <li>when the player encounters a playback error</li>
     * <li>when the a seek operation starts, and when it's completed</li>
     * <li>when the playback speed changes</li>
     * <li>when the playback state changes</li>
     * <li>when the player is reset</li>
     * </ul>
     * See the
     * {@link MediaPlayer#setOnMediaTimeDiscontinuityListener(OnMediaTimeDiscontinuityListener, Handler)}
     * method to set a listener for these events.
     */
    public interface OnMediaTimeDiscontinuityListener {
        /**
         * Called to indicate a time discontinuity has occured.
         * @param mp the MediaPlayer for which the discontinuity has occured.
         * @param mts the timestamp that correlates media time, system time and clock rate,
         *     or {@link MediaTimestamp#TIMESTAMP_UNKNOWN} in an error case.
         */
        public void onMediaTimeDiscontinuity(@NonNull MediaPlayer mp, @NonNull MediaTimestamp mts);
    }

    /**
     * Sets the listener to be invoked when a media time discontinuity is encountered.
     * @param listener the listener called after a discontinuity
     * @param handler the {@link Handler} that receives the listener events
     */
    public void setOnMediaTimeDiscontinuityListener(
            @NonNull OnMediaTimeDiscontinuityListener listener, @NonNull Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("Illegal null listener");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Illegal null handler");
        }
        setOnMediaTimeDiscontinuityListenerInt(listener, handler);
    }

    /**
     * Sets the listener to be invoked when a media time discontinuity is encountered.
     * The listener will be called on the same thread as the one in which the MediaPlayer was
     * created.
     * @param listener the listener called after a discontinuity
     */
    public void setOnMediaTimeDiscontinuityListener(
            @NonNull OnMediaTimeDiscontinuityListener listener)
    {
        if (listener == null) {
            throw new IllegalArgumentException("Illegal null listener");
        }
        setOnMediaTimeDiscontinuityListenerInt(listener, null);
    }

    /**
     * Clears the listener previously set with
     * {@link #setOnMediaTimeDiscontinuityListener(OnMediaTimeDiscontinuityListener)}
     * or {@link #setOnMediaTimeDiscontinuityListener(OnMediaTimeDiscontinuityListener, Handler)}
     */
    public void clearOnMediaTimeDiscontinuityListener() {
        setOnMediaTimeDiscontinuityListenerInt(null, null);
    }

    private void setOnMediaTimeDiscontinuityListenerInt(
            @Nullable OnMediaTimeDiscontinuityListener listener, @Nullable Handler handler) {
        synchronized (this) {
            mOnMediaTimeDiscontinuityListener = listener;
            mOnMediaTimeDiscontinuityHandler = handler;
        }
    }

    private OnMediaTimeDiscontinuityListener mOnMediaTimeDiscontinuityListener;
    private Handler mOnMediaTimeDiscontinuityHandler;

    /**
     * Interface definition of a callback to be invoked when a
     * track has timed metadata available.
     *
     * @see MediaPlayer#setOnTimedMetaDataAvailableListener(OnTimedMetaDataAvailableListener)
     */
    public interface OnTimedMetaDataAvailableListener
    {
        /**
         * Called to indicate avaliable timed metadata
         * <p>
         * This method will be called as timed metadata is extracted from the media,
         * in the same order as it occurs in the media. The timing of this event is
         * not controlled by the associated timestamp.
         *
         * @param mp             the MediaPlayer associated with this callback
         * @param data           the timed metadata sample associated with this event
         */
        public void onTimedMetaDataAvailable(MediaPlayer mp, TimedMetaData data);
    }

    /**
     * Interface definition of a callback to be invoked when
     * RTP Rx connection has a notice.
     *
     * @see #setOnRtpRxNoticeListener
     *
     * @hide
     */
    @SystemApi
    public interface OnRtpRxNoticeListener
    {
        /**
         * Called when an RTP Rx connection has a notice.
         * <p>
         * Basic format. All TYPE and ARG are 4 bytes unsigned integer in native byte order.
         * <pre>{@code
         * 0                4               8                12
         * +----------------+---------------+----------------+----------------+
         * |      TYPE      |      ARG1     |      ARG2      |      ARG3      |
         * +----------------+---------------+----------------+----------------+
         * |      ARG4      |      ARG5     |      ...
         * +----------------+---------------+-------------
         * 16               20              24
         *
         *
         * TYPE 100 - A notice of the first rtp packet received. No ARGs.
         * 0
         * +----------------+
         * |      100       |
         * +----------------+
         *
         *
         * TYPE 101 - A notice of the first rtcp packet received. No ARGs.
         * 0
         * +----------------+
         * |      101       |
         * +----------------+
         *
         *
         * TYPE 102 - A periodic report of a RTP statistics.
         * TYPE 103 - An emergency report when serious packet loss has been detected
         *            in between TYPE 102 events.
         * 0                4               8                12
         * +----------------+---------------+----------------+----------------+
         * |   102 or 103   |   FB type=0   |    Bitrate     |   Top #.Seq    |
         * +----------------+---------------+----------------+----------------+
         * |   Base #.Seq   |Prev Expt #.Pkt|   Recv #.Pkt   |Prev Recv #.Pkt |
         * +----------------+---------------+----------------+----------------+
         * Feedback (FB) type
         *      - always 0.
         * Bitrate
         *      - amount of data received in this period.
         * Top number of sequence
         *      - highest RTP sequence number received in this period.
         *      - monotonically increasing value.
         * Base number of sequence
         *      - the first RTP sequence number of the media stream.
         * Previous Expected number of Packets
         *      - expected count of packets received in the previous report.
         * Received number of packet
         *      - actual count of packets received in this report.
         * Previous Received number of packet
         *      - actual count of packets received in the previous report.
         *
         *
         * TYPE 205 - Transport layer Feedback message. (RFC-5104 Sec.4.2)
         * 0                4               8                12
         * +----------------+---------------+----------------+----------------+
         * |      205       |FB type(1 or 3)|      SSRC      |      Value     |
         * +----------------+---------------+----------------+----------------+
         * Feedback (FB) type: determines the type of the event.
         *      - if 1, we received a NACK request from the remote side.
         *      - if 3, we received a TMMBR (Temporary Maximum Media Stream Bit Rate Request) from
         *        the remote side.
         * SSRC
         *      - Remote side's SSRC value of the media sender (RFC-3550 Sec.5.1)
         * Value: the FCI (Feedback Control Information) depending on the value of FB type
         *      - if FB type is 1, the Generic NACK as specified in RFC-4585 Sec.6.2.1
         *      - if FB type is 3, the TMMBR as specified in RFC-5104 Sec.4.2.1.1
         *
         *
         * TYPE 206 - Payload-specific Feedback message. (RFC-5104 Sec.4.3)
         * 0                4               8
         * +----------------+---------------+----------------+
         * |      206       |FB type(1 or 4)|      SSRC      |
         * +----------------+---------------+----------------+
         * Feedback (FB) type: determines the type of the event.
         *      - if 1, we received a PLI request from the remote side.
         *      - if 4, we received a FIR request from the remote side.
         * SSRC
         *      - Remote side's SSRC value of the media sender (RFC-3550 Sec.5.1)
         *
         *
         * TYPE 300 - CVO (RTP Extension) message.
         * 0                4
         * +----------------+---------------+
         * |      101       |     value     |
         * +----------------+---------------+
         * value
         *      - clockwise rotation degrees of a received video (6.2.3 of 3GPP R12 TS 26.114).
         *      - can be 0 (degree 0), 1 (degree 90), 2 (degree 180) or 3 (degree 270).
         *
         *
         * TYPE 400 - Socket failed during receive. No ARGs.
         * 0
         * +----------------+
         * |      400       |
         * +----------------+
         * }</pre>
         *
         * @param mp the {@code MediaPlayer} associated with this callback.
         * @param noticeType TYPE of the event.
         * @param params RTP Rx media data serialized as int[] array.
         */
        void onRtpRxNotice(@NonNull MediaPlayer mp, int noticeType, @NonNull int[] params);
    }

    /**
     * Sets the listener to be invoked when an RTP Rx connection has a notice.
     * The listener is required if MediaPlayer is configured for RTPSource by
     * MediaPlayer.setDataSource(String8 rtpParams) of mediaplayer.h.
     *
     * @see OnRtpRxNoticeListener
     *
     * @param listener the listener called after a notice from RTP Rx.
     * @param executor the {@link Executor} on which to post RTP Tx events.
     * @hide
     */
    @SystemApi
    @RequiresPermission(BIND_IMS_SERVICE)
    public void setOnRtpRxNoticeListener(
            @NonNull Context context,
            @NonNull Executor executor,
            @NonNull OnRtpRxNoticeListener listener) {
        Objects.requireNonNull(context);
        Preconditions.checkArgument(
                context.checkSelfPermission(BIND_IMS_SERVICE) == PERMISSION_GRANTED,
                BIND_IMS_SERVICE + " permission not granted.");
        mOnRtpRxNoticeListener = Objects.requireNonNull(listener);
        mOnRtpRxNoticeExecutor = Objects.requireNonNull(executor);
    }

    private OnRtpRxNoticeListener mOnRtpRxNoticeListener;
    private Executor mOnRtpRxNoticeExecutor;

    /**
     * Register a callback to be invoked when a selected track has timed metadata available.
     * <p>
     * Currently only HTTP live streaming data URI's embedded with timed ID3 tags generates
     * {@link TimedMetaData}.
     *
     * @see MediaPlayer#selectTrack(int)
     * @see MediaPlayer.OnTimedMetaDataAvailableListener
     * @see TimedMetaData
     *
     * @param listener the callback that will be run
     */
    public void setOnTimedMetaDataAvailableListener(OnTimedMetaDataAvailableListener listener)
    {
        mOnTimedMetaDataAvailableListener = listener;
    }

    private OnTimedMetaDataAvailableListener mOnTimedMetaDataAvailableListener;

    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer.h!
     */
    /** Unspecified media player error.
     * @see android.media.MediaPlayer.OnErrorListener
     */
    public static final int MEDIA_ERROR_UNKNOWN = 1;

    /** Media server died. In this case, the application must release the
     * MediaPlayer object and instantiate a new one.
     * @see android.media.MediaPlayer.OnErrorListener
     */
    public static final int MEDIA_ERROR_SERVER_DIED = 100;

    /** The video is streamed and its container is not valid for progressive
     * playback i.e the video's index (e.g moov atom) is not at the start of the
     * file.
     * @see android.media.MediaPlayer.OnErrorListener
     */
    public static final int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200;

    /** File or network related operation errors. */
    public static final int MEDIA_ERROR_IO = -1004;
    /** Bitstream is not conforming to the related coding standard or file spec. */
    public static final int MEDIA_ERROR_MALFORMED = -1007;
    /** Bitstream is conforming to the related coding standard or file spec, but
     * the media framework does not support the feature. */
    public static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    /** Some operation takes too long to complete, usually more than 3-5 seconds. */
    public static final int MEDIA_ERROR_TIMED_OUT = -110;

    /** Unspecified low-level system error. This value originated from UNKNOWN_ERROR in
     * system/core/include/utils/Errors.h
     * @see android.media.MediaPlayer.OnErrorListener
     * @hide
     */
    public static final int MEDIA_ERROR_SYSTEM = -2147483648;

    /**
     * Interface definition of a callback to be invoked when there
     * has been an error during an asynchronous operation (other errors
     * will throw exceptions at method call time).
     */
    public interface OnErrorListener
    {
        /**
         * Called to indicate an error.
         *
         * @param mp      the MediaPlayer the error pertains to
         * @param what    the type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIA_ERROR_UNKNOWN}
         * <li>{@link #MEDIA_ERROR_SERVER_DIED}
         * </ul>
         * @param extra an extra code, specific to the error. Typically
         * implementation dependent.
         * <ul>
         * <li>{@link #MEDIA_ERROR_IO}
         * <li>{@link #MEDIA_ERROR_MALFORMED}
         * <li>{@link #MEDIA_ERROR_UNSUPPORTED}
         * <li>{@link #MEDIA_ERROR_TIMED_OUT}
         * <li><code>MEDIA_ERROR_SYSTEM (-2147483648)</code> - low-level system error.
         * </ul>
         * @return True if the method handled the error, false if it didn't.
         * Returning false, or not having an OnErrorListener at all, will
         * cause the OnCompletionListener to be called.
         */
        boolean onError(MediaPlayer mp, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an error has happened
     * during an asynchronous operation.
     *
     * @param listener the callback that will be run
     */
    public void setOnErrorListener(OnErrorListener listener)
    {
        mOnErrorListener = listener;
    }

    @UnsupportedAppUsage
    private OnErrorListener mOnErrorListener;


    /* Do not change these values without updating their counterparts
     * in include/media/mediaplayer.h!
     */
    /** Unspecified media player info.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_UNKNOWN = 1;

    /** The player was started because it was used as the next player for another
     * player, which just completed playback.
     * @see android.media.MediaPlayer#setNextMediaPlayer(MediaPlayer)
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_STARTED_AS_NEXT = 2;

    /** The player just pushed the very first video frame for rendering.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;

    /** The video is too complex for the decoder: it can't decode frames fast
     *  enough. Possibly only the audio plays fine at this stage.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_VIDEO_TRACK_LAGGING = 700;

    /** MediaPlayer is temporarily pausing playback internally in order to
     * buffer more data.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_BUFFERING_START = 701;

    /** MediaPlayer is resuming playback after filling buffers.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_BUFFERING_END = 702;

    /** Estimated network bandwidth information (kbps) is available; currently this event fires
     * simultaneously as {@link #MEDIA_INFO_BUFFERING_START} and {@link #MEDIA_INFO_BUFFERING_END}
     * when playing network files.
     * @see android.media.MediaPlayer.OnInfoListener
     * @hide
     */
    public static final int MEDIA_INFO_NETWORK_BANDWIDTH = 703;

    /** Bad interleaving means that a media has been improperly interleaved or
     * not interleaved at all, e.g has all the video samples first then all the
     * audio ones. Video is playing but a lot of disk seeks may be happening.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_BAD_INTERLEAVING = 800;

    /** The media cannot be seeked (e.g live stream)
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_NOT_SEEKABLE = 801;

    /** A new set of metadata is available.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_METADATA_UPDATE = 802;

    /** A new set of external-only metadata is available.  Used by
     *  JAVA framework to avoid triggering track scanning.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int MEDIA_INFO_EXTERNAL_METADATA_UPDATE = 803;

    /** Informs that audio is not playing. Note that playback of the video
     * is not interrupted.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_AUDIO_NOT_PLAYING = 804;

    /** Informs that video is not playing. Note that playback of the audio
     * is not interrupted.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_VIDEO_NOT_PLAYING = 805;

    /** Failed to handle timed text track properly.
     * @see android.media.MediaPlayer.OnInfoListener
     *
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int MEDIA_INFO_TIMED_TEXT_ERROR = 900;

    /** Subtitle track was not supported by the media framework.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_UNSUPPORTED_SUBTITLE = 901;

    /** Reading the subtitle track takes too long.
     * @see android.media.MediaPlayer.OnInfoListener
     */
    public static final int MEDIA_INFO_SUBTITLE_TIMED_OUT = 902;

    /**
     * Interface definition of a callback to be invoked to communicate some
     * info and/or warning about the media or its playback.
     */
    public interface OnInfoListener
    {
        /**
         * Called to indicate an info or a warning.
         *
         * @param mp      the MediaPlayer the info pertains to.
         * @param what    the type of info or warning.
         * <ul>
         * <li>{@link #MEDIA_INFO_UNKNOWN}
         * <li>{@link #MEDIA_INFO_VIDEO_TRACK_LAGGING}
         * <li>{@link #MEDIA_INFO_VIDEO_RENDERING_START}
         * <li>{@link #MEDIA_INFO_BUFFERING_START}
         * <li>{@link #MEDIA_INFO_BUFFERING_END}
         * <li><code>MEDIA_INFO_NETWORK_BANDWIDTH (703)</code> -
         *     bandwidth information is available (as <code>extra</code> kbps)
         * <li>{@link #MEDIA_INFO_BAD_INTERLEAVING}
         * <li>{@link #MEDIA_INFO_NOT_SEEKABLE}
         * <li>{@link #MEDIA_INFO_METADATA_UPDATE}
         * <li>{@link #MEDIA_INFO_UNSUPPORTED_SUBTITLE}
         * <li>{@link #MEDIA_INFO_SUBTITLE_TIMED_OUT}
         * </ul>
         * @param extra an extra code, specific to the info. Typically
         * implementation dependent.
         * @return True if the method handled the info, false if it didn't.
         * Returning false, or not having an OnInfoListener at all, will
         * cause the info to be discarded.
         */
        boolean onInfo(MediaPlayer mp, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an info/warning is available.
     *
     * @param listener the callback that will be run
     */
    public void setOnInfoListener(OnInfoListener listener)
    {
        mOnInfoListener = listener;
    }

    @UnsupportedAppUsage
    private OnInfoListener mOnInfoListener;

    // Modular DRM begin

    /**
     * Interface definition of a callback to be invoked when the app
     * can do DRM configuration (get/set properties) before the session
     * is opened. This facilitates configuration of the properties, like
     * 'securityLevel', which has to be set after DRM scheme creation but
     * before the DRM session is opened.
     *
     * The only allowed DRM calls in this listener are {@code getDrmPropertyString}
     * and {@code setDrmPropertyString}.
     *
     */
    public interface OnDrmConfigHelper
    {
        /**
         * Called to give the app the opportunity to configure DRM before the session is created
         *
         * @param mp the {@code MediaPlayer} associated with this callback
         */
        public void onDrmConfig(MediaPlayer mp);
    }

    /**
     * Register a callback to be invoked for configuration of the DRM object before
     * the session is created.
     * The callback will be invoked synchronously during the execution
     * of {@link #prepareDrm(UUID uuid)}.
     *
     * @param listener the callback that will be run
     */
    public void setOnDrmConfigHelper(OnDrmConfigHelper listener)
    {
        synchronized (mDrmLock) {
            mOnDrmConfigHelper = listener;
        } // synchronized
    }

    private OnDrmConfigHelper mOnDrmConfigHelper;

    /**
     * Interface definition of a callback to be invoked when the
     * DRM info becomes available
     */
    public interface OnDrmInfoListener
    {
        /**
         * Called to indicate DRM info is available
         *
         * @param mp the {@code MediaPlayer} associated with this callback
         * @param drmInfo DRM info of the source including PSSH, and subset
         *                of crypto schemes supported by this device
         */
        public void onDrmInfo(MediaPlayer mp, DrmInfo drmInfo);
    }

    /**
     * Register a callback to be invoked when the DRM info is
     * known.
     *
     * @param listener the callback that will be run
     */
    public void setOnDrmInfoListener(OnDrmInfoListener listener)
    {
        setOnDrmInfoListener(listener, null);
    }

    /**
     * Register a callback to be invoked when the DRM info is
     * known.
     *
     * @param listener the callback that will be run
     */
    public void setOnDrmInfoListener(OnDrmInfoListener listener, Handler handler)
    {
        synchronized (mDrmLock) {
            if (listener != null) {
                mOnDrmInfoHandlerDelegate = new OnDrmInfoHandlerDelegate(this, listener, handler);
            } else {
                mOnDrmInfoHandlerDelegate = null;
            }
        } // synchronized
    }

    private OnDrmInfoHandlerDelegate mOnDrmInfoHandlerDelegate;


    /**
     * The status codes for {@link OnDrmPreparedListener#onDrmPrepared} listener.
     * <p>
     *
     * DRM preparation has succeeded.
     */
    public static final int PREPARE_DRM_STATUS_SUCCESS = 0;

    /**
     * The device required DRM provisioning but couldn't reach the provisioning server.
     */
    public static final int PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR = 1;

    /**
     * The device required DRM provisioning but the provisioning server denied the request.
     */
    public static final int PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR = 2;

    /**
     * The DRM preparation has failed .
     */
    public static final int PREPARE_DRM_STATUS_PREPARATION_ERROR = 3;


    /** @hide */
    @IntDef({
        PREPARE_DRM_STATUS_SUCCESS,
        PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR,
        PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR,
        PREPARE_DRM_STATUS_PREPARATION_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrepareDrmStatusCode {}

    /**
     * Interface definition of a callback to notify the app when the
     * DRM is ready for key request/response
     */
    public interface OnDrmPreparedListener
    {
        /**
         * Called to notify the app that prepareDrm is finished and ready for key request/response
         *
         * @param mp the {@code MediaPlayer} associated with this callback
         * @param status the result of DRM preparation which can be
         * {@link #PREPARE_DRM_STATUS_SUCCESS},
         * {@link #PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR},
         * {@link #PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR}, or
         * {@link #PREPARE_DRM_STATUS_PREPARATION_ERROR}.
         */
        public void onDrmPrepared(MediaPlayer mp, @PrepareDrmStatusCode int status);
    }

    /**
     * Register a callback to be invoked when the DRM object is prepared.
     *
     * @param listener the callback that will be run
     */
    public void setOnDrmPreparedListener(OnDrmPreparedListener listener)
    {
        setOnDrmPreparedListener(listener, null);
    }

    /**
     * Register a callback to be invoked when the DRM object is prepared.
     *
     * @param listener the callback that will be run
     * @param handler the Handler that will receive the callback
     */
    public void setOnDrmPreparedListener(OnDrmPreparedListener listener, Handler handler)
    {
        synchronized (mDrmLock) {
            if (listener != null) {
                mOnDrmPreparedHandlerDelegate = new OnDrmPreparedHandlerDelegate(this,
                                                            listener, handler);
            } else {
                mOnDrmPreparedHandlerDelegate = null;
            }
        } // synchronized
    }

    private OnDrmPreparedHandlerDelegate mOnDrmPreparedHandlerDelegate;


    private class OnDrmInfoHandlerDelegate {
        private MediaPlayer mMediaPlayer;
        private OnDrmInfoListener mOnDrmInfoListener;
        private Handler mHandler;

        OnDrmInfoHandlerDelegate(MediaPlayer mp, OnDrmInfoListener listener, Handler handler) {
            mMediaPlayer = mp;
            mOnDrmInfoListener = listener;

            // find the looper for our new event handler
            if (handler != null) {
                mHandler = handler;
            } else {
                // handler == null
                // Will let OnDrmInfoListener be called in mEventHandler similar to other
                // legacy notifications. This is because MEDIA_DRM_INFO's notification has to be
                // sent before MEDIA_PREPARED's (i.e., in the same order they are issued by
                // mediaserver). As a result, the callback has to be called directly by
                // EventHandler.handleMessage similar to onPrepared.
            }
        }

        void notifyClient(DrmInfo drmInfo) {
            if (mHandler != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                       mOnDrmInfoListener.onDrmInfo(mMediaPlayer, drmInfo);
                    }
                });
            }
            else {  // no handler: direct call by mEventHandler
                mOnDrmInfoListener.onDrmInfo(mMediaPlayer, drmInfo);
            }
        }
    }

    private class OnDrmPreparedHandlerDelegate {
        private MediaPlayer mMediaPlayer;
        private OnDrmPreparedListener mOnDrmPreparedListener;
        private Handler mHandler;

        OnDrmPreparedHandlerDelegate(MediaPlayer mp, OnDrmPreparedListener listener,
                Handler handler) {
            mMediaPlayer = mp;
            mOnDrmPreparedListener = listener;

            // find the looper for our new event handler
            if (handler != null) {
                mHandler = handler;
            } else if (mEventHandler != null) {
                // Otherwise, use mEventHandler
                mHandler = mEventHandler;
            } else {
                Log.e(TAG, "OnDrmPreparedHandlerDelegate: Unexpected null mEventHandler");
            }
        }

        void notifyClient(int status) {
            if (mHandler != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mOnDrmPreparedListener.onDrmPrepared(mMediaPlayer, status);
                    }
                });
            } else {
                Log.e(TAG, "OnDrmPreparedHandlerDelegate:notifyClient: Unexpected null mHandler");
            }
        }
    }

    /**
     * Retrieves the DRM Info associated with the current source
     *
     * @throws IllegalStateException if called before prepare()
     */
    public DrmInfo getDrmInfo()
    {
        DrmInfo drmInfo = null;

        // there is not much point if the app calls getDrmInfo within an OnDrmInfoListenet;
        // regardless below returns drmInfo anyway instead of raising an exception
        synchronized (mDrmLock) {
            if (!mDrmInfoResolved && mDrmInfo == null) {
                final String msg = "The Player has not been prepared yet";
                Log.v(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mDrmInfo != null) {
                drmInfo = mDrmInfo.makeCopy();
            }
        }   // synchronized

        return drmInfo;
    }


    /**
     * Prepares the DRM for the current source
     * <p>
     * If {@code OnDrmConfigHelper} is registered, it will be called during
     * preparation to allow configuration of the DRM properties before opening the
     * DRM session. Note that the callback is called synchronously in the thread that called
     * {@code prepareDrm}. It should be used only for a series of {@code getDrmPropertyString}
     * and {@code setDrmPropertyString} calls and refrain from any lengthy operation.
     * <p>
     * If the device has not been provisioned before, this call also provisions the device
     * which involves accessing the provisioning server and can take a variable time to
     * complete depending on the network connectivity.
     * If {@code OnDrmPreparedListener} is registered, prepareDrm() runs in non-blocking
     * mode by launching the provisioning in the background and returning. The listener
     * will be called when provisioning and preparation has finished. If a
     * {@code OnDrmPreparedListener} is not registered, prepareDrm() waits till provisioning
     * and preparation has finished, i.e., runs in blocking mode.
     * <p>
     * If {@code OnDrmPreparedListener} is registered, it is called to indicate the DRM
     * session being ready. The application should not make any assumption about its call
     * sequence (e.g., before or after prepareDrm returns), or the thread context that will
     * execute the listener (unless the listener is registered with a handler thread).
     * <p>
     *
     * @param uuid The UUID of the crypto scheme. If not known beforehand, it can be retrieved
     * from the source through {@code getDrmInfo} or registering a {@code onDrmInfoListener}.
     *
     * @throws IllegalStateException              if called before prepare(), or the DRM was
     *                                            prepared already
     * @throws UnsupportedSchemeException         if the crypto scheme is not supported
     * @throws ResourceBusyException              if required DRM resources are in use
     * @throws ProvisioningNetworkErrorException  if provisioning is required but failed due to a
     *                                            network error
     * @throws ProvisioningServerErrorException   if provisioning is required but failed due to
     *                                            the request denied by the provisioning server
     */
    public void prepareDrm(@NonNull UUID uuid)
            throws UnsupportedSchemeException, ResourceBusyException,
                   ProvisioningNetworkErrorException, ProvisioningServerErrorException
    {
        Log.v(TAG, "prepareDrm: uuid: " + uuid + " mOnDrmConfigHelper: " + mOnDrmConfigHelper);

        boolean allDoneWithoutProvisioning = false;
        // get a snapshot as we'll use them outside the lock
        OnDrmPreparedHandlerDelegate onDrmPreparedHandlerDelegate = null;

        synchronized (mDrmLock) {

            // only allowing if tied to a protected source; might relax for releasing offline keys
            if (mDrmInfo == null) {
                final String msg = "prepareDrm(): Wrong usage: The player must be prepared and " +
                        "DRM info be retrieved before this call.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mActiveDrmScheme) {
                final String msg = "prepareDrm(): Wrong usage: There is already " +
                        "an active DRM scheme with " + mDrmUUID;
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mPrepareDrmInProgress) {
                final String msg = "prepareDrm(): Wrong usage: There is already " +
                        "a pending prepareDrm call.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            if (mDrmProvisioningInProgress) {
                final String msg = "prepareDrm(): Unexpectd: Provisioning is already in progress.";
                Log.e(TAG, msg);
                throw new IllegalStateException(msg);
            }

            // shouldn't need this; just for safeguard
            cleanDrmObj();

            mPrepareDrmInProgress = true;
            // local copy while the lock is held
            onDrmPreparedHandlerDelegate = mOnDrmPreparedHandlerDelegate;

            try {
                // only creating the DRM object to allow pre-openSession configuration
                prepareDrm_createDrmStep(uuid);
            } catch (Exception e) {
                Log.w(TAG, "prepareDrm(): Exception ", e);
                mPrepareDrmInProgress = false;
                throw e;
            }

            mDrmConfigAllowed = true;
        }   // synchronized


        // call the callback outside the lock
        if (mOnDrmConfigHelper != null)  {
            mOnDrmConfigHelper.onDrmConfig(this);
        }

        synchronized (mDrmLock) {
            mDrmConfigAllowed = false;
            boolean earlyExit = false;

            try {
                prepareDrm_openSessionStep(uuid);

                mDrmUUID = uuid;
                mActiveDrmScheme = true;

                allDoneWithoutProvisioning = true;
            } catch (IllegalStateException e) {
                final String msg = "prepareDrm(): Wrong usage: The player must be " +
                        "in the prepared state to call prepareDrm().";
                Log.e(TAG, msg);
                earlyExit = true;
                throw new IllegalStateException(msg);
            } catch (NotProvisionedException e) {
                Log.w(TAG, "prepareDrm: NotProvisionedException");

                // handle provisioning internally; it'll reset mPrepareDrmInProgress
                int result = HandleProvisioninig(uuid);

                // if blocking mode, we're already done;
                // if non-blocking mode, we attempted to launch background provisioning
                if (result != PREPARE_DRM_STATUS_SUCCESS) {
                    earlyExit = true;
                    String msg;

                    switch (result) {
                    case PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR:
                        msg = "prepareDrm: Provisioning was required but failed " +
                                "due to a network error.";
                        Log.e(TAG, msg);
                        throw new ProvisioningNetworkErrorException(msg);

                    case PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR:
                        msg = "prepareDrm: Provisioning was required but the request " +
                                "was denied by the server.";
                        Log.e(TAG, msg);
                        throw new ProvisioningServerErrorException(msg);

                    case PREPARE_DRM_STATUS_PREPARATION_ERROR:
                    default: // default for safeguard
                        msg = "prepareDrm: Post-provisioning preparation failed.";
                        Log.e(TAG, msg);
                        throw new IllegalStateException(msg);
                    }
                }
                // nothing else to do;
                // if blocking or non-blocking, HandleProvisioninig does the re-attempt & cleanup
            } catch (Exception e) {
                Log.e(TAG, "prepareDrm: Exception " + e);
                earlyExit = true;
                throw e;
            } finally {
                if (!mDrmProvisioningInProgress) {// if early exit other than provisioning exception
                    mPrepareDrmInProgress = false;
                }
                if (earlyExit) {    // cleaning up object if didn't succeed
                    cleanDrmObj();
                }
            } // finally
        }   // synchronized


        // if finished successfully without provisioning, call the callback outside the lock
        if (allDoneWithoutProvisioning) {
            if (onDrmPreparedHandlerDelegate != null)
                onDrmPreparedHandlerDelegate.notifyClient(PREPARE_DRM_STATUS_SUCCESS);
        }

    }


    private native void _releaseDrm();

    /**
     * Releases the DRM session
     * <p>
     * The player has to have an active DRM session and be in stopped, or prepared
     * state before this call is made.
     * A {@code reset()} call will release the DRM session implicitly.
     *
     * @throws NoDrmSchemeException if there is no active DRM session to release
     */
    public void releaseDrm()
            throws NoDrmSchemeException
    {
        Log.v(TAG, "releaseDrm:");

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.e(TAG, "releaseDrm(): No active DRM scheme to release.");
                throw new NoDrmSchemeException("releaseDrm: No active DRM scheme to release.");
            }

            try {
                // we don't have the player's state in this layer. The below call raises
                // exception if we're in a non-stopped/prepared state.

                // for cleaning native/mediaserver crypto object
                _releaseDrm();

                // for cleaning client-side MediaDrm object; only called if above has succeeded
                cleanDrmObj();

                mActiveDrmScheme = false;
            } catch (IllegalStateException e) {
                Log.w(TAG, "releaseDrm: Exception ", e);
                throw new IllegalStateException("releaseDrm: The player is not in a valid state.");
            } catch (Exception e) {
                Log.e(TAG, "releaseDrm: Exception ", e);
            }
        }   // synchronized
    }


    /**
     * A key request/response exchange occurs between the app and a license server
     * to obtain or release keys used to decrypt encrypted content.
     * <p>
     * getKeyRequest() is used to obtain an opaque key request byte array that is
     * delivered to the license server.  The opaque key request byte array is returned
     * in KeyRequest.data.  The recommended URL to deliver the key request to is
     * returned in KeyRequest.defaultUrl.
     * <p>
     * After the app has received the key request response from the server,
     * it should deliver to the response to the DRM engine plugin using the method
     * {@link #provideKeyResponse}.
     *
     * @param keySetId is the key-set identifier of the offline keys being released when keyType is
     * {@link MediaDrm#KEY_TYPE_RELEASE}. It should be set to null for other key requests, when
     * keyType is {@link MediaDrm#KEY_TYPE_STREAMING} or {@link MediaDrm#KEY_TYPE_OFFLINE}.
     *
     * @param initData is the container-specific initialization data when the keyType is
     * {@link MediaDrm#KEY_TYPE_STREAMING} or {@link MediaDrm#KEY_TYPE_OFFLINE}. Its meaning is
     * interpreted based on the mime type provided in the mimeType parameter.  It could
     * contain, for example, the content ID, key ID or other data obtained from the content
     * metadata that is required in generating the key request.
     * When the keyType is {@link MediaDrm#KEY_TYPE_RELEASE}, it should be set to null.
     *
     * @param mimeType identifies the mime type of the content
     *
     * @param keyType specifies the type of the request. The request may be to acquire
     * keys for streaming, {@link MediaDrm#KEY_TYPE_STREAMING}, or for offline content
     * {@link MediaDrm#KEY_TYPE_OFFLINE}, or to release previously acquired
     * keys ({@link MediaDrm#KEY_TYPE_RELEASE}), which are identified by a keySetId.
     *
     * @param optionalParameters are included in the key request message to
     * allow a client application to provide additional message parameters to the server.
     * This may be {@code null} if no additional parameters are to be sent.
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     */
    @NonNull
    public MediaDrm.KeyRequest getKeyRequest(@Nullable byte[] keySetId, @Nullable byte[] initData,
            @Nullable String mimeType, @MediaDrm.KeyType int keyType,
            @Nullable Map<String, String> optionalParameters)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "getKeyRequest: " +
                " keySetId: " + keySetId + " initData:" + initData + " mimeType: " + mimeType +
                " keyType: " + keyType + " optionalParameters: " + optionalParameters);

        synchronized (mDrmLock) {
            if (!mActiveDrmScheme) {
                Log.e(TAG, "getKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeException("getKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keyType != MediaDrm.KEY_TYPE_RELEASE) ?
                        mDrmSessionId : // sessionId for KEY_TYPE_STREAMING/OFFLINE
                        keySetId;       // keySetId for KEY_TYPE_RELEASE

                HashMap<String, String> hmapOptionalParameters =
                                                (optionalParameters != null) ?
                                                new HashMap<String, String>(optionalParameters) :
                                                null;

                MediaDrm.KeyRequest request = mDrmObj.getKeyRequest(scope, initData, mimeType,
                                                              keyType, hmapOptionalParameters);
                Log.v(TAG, "getKeyRequest:   --> request: " + request);

                return request;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "getKeyRequest NotProvisionedException: " +
                        "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("getKeyRequest: Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "getKeyRequest Exception " + e);
                throw e;
            }

        }   // synchronized
    }


    /**
     * A key response is received from the license server by the app, then it is
     * provided to the DRM engine plugin using provideKeyResponse. When the
     * response is for an offline key request, a key-set identifier is returned that
     * can be used to later restore the keys to a new session with the method
     * {@ link # restoreKeys}.
     * When the response is for a streaming or release request, null is returned.
     *
     * @param keySetId When the response is for a release request, keySetId identifies
     * the saved key associated with the release request (i.e., the same keySetId
     * passed to the earlier {@ link # getKeyRequest} call. It MUST be null when the
     * response is for either streaming or offline key requests.
     *
     * @param response the byte array response from the server
     *
     * @throws NoDrmSchemeException if there is no active DRM session
     * @throws DeniedByServerException if the response indicates that the
     * server rejected the request
     */
    public byte[] provideKeyResponse(@Nullable byte[] keySetId, @NonNull byte[] response)
            throws NoDrmSchemeException, DeniedByServerException
    {
        Log.v(TAG, "provideKeyResponse: keySetId: " + keySetId + " response: " + response);

        synchronized (mDrmLock) {

            if (!mActiveDrmScheme) {
                Log.e(TAG, "getKeyRequest NoDrmSchemeException");
                throw new NoDrmSchemeException("getKeyRequest: Has to set a DRM scheme first.");
            }

            try {
                byte[] scope = (keySetId == null) ?
                                mDrmSessionId :     // sessionId for KEY_TYPE_STREAMING/OFFLINE
                                keySetId;           // keySetId for KEY_TYPE_RELEASE

                byte[] keySetResult = mDrmObj.provideKeyResponse(scope, response);

                Log.v(TAG, "provideKeyResponse: keySetId: " + keySetId + " response: " + response +
                        " --> " + keySetResult);


                return keySetResult;

            } catch (NotProvisionedException e) {
                Log.w(TAG, "provideKeyResponse NotProvisionedException: " +
                        "Unexpected. Shouldn't have reached here.");
                throw new IllegalStateException("provideKeyResponse: " +
                        "Unexpected provisioning error.");
            } catch (Exception e) {
                Log.w(TAG, "provideKeyResponse Exception " + e);
                throw e;
            }
        }   // synchronized
    }


    /**
     * Restore persisted offline keys into a new session.  keySetId identifies the
     * keys to load, obtained from a prior call to {@link #provideKeyResponse}.
     *
     * @param keySetId identifies the saved key set to restore
     */
    public void restoreKeys(@NonNull byte[] keySetId)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "restoreKeys: keySetId: " + keySetId);

        synchronized (mDrmLock) {

            if (!mActiveDrmScheme) {
                Log.w(TAG, "restoreKeys NoDrmSchemeException");
                throw new NoDrmSchemeException("restoreKeys: Has to set a DRM scheme first.");
            }

            try {
                mDrmObj.restoreKeys(mDrmSessionId, keySetId);
            } catch (Exception e) {
                Log.w(TAG, "restoreKeys Exception " + e);
                throw e;
            }

        }   // synchronized
    }


    /**
     * Read a DRM engine plugin String property value, given the property name string.
     * <p>
     * @param propertyName the property name
     *
     * Standard fields names are:
     * {@link MediaDrm#PROPERTY_VENDOR}, {@link MediaDrm#PROPERTY_VERSION},
     * {@link MediaDrm#PROPERTY_DESCRIPTION}, {@link MediaDrm#PROPERTY_ALGORITHMS}
     */
    @NonNull
    public String getDrmPropertyString(@NonNull @MediaDrm.StringProperty String propertyName)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "getDrmPropertyString: propertyName: " + propertyName);

        String value;
        synchronized (mDrmLock) {

            if (!mActiveDrmScheme && !mDrmConfigAllowed) {
                Log.w(TAG, "getDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeException("getDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                value = mDrmObj.getPropertyString(propertyName);
            } catch (Exception e) {
                Log.w(TAG, "getDrmPropertyString Exception " + e);
                throw e;
            }
        }   // synchronized

        Log.v(TAG, "getDrmPropertyString: propertyName: " + propertyName + " --> value: " + value);

        return value;
    }


    /**
     * Set a DRM engine plugin String property value.
     * <p>
     * @param propertyName the property name
     * @param value the property value
     *
     * Standard fields names are:
     * {@link MediaDrm#PROPERTY_VENDOR}, {@link MediaDrm#PROPERTY_VERSION},
     * {@link MediaDrm#PROPERTY_DESCRIPTION}, {@link MediaDrm#PROPERTY_ALGORITHMS}
     */
    public void setDrmPropertyString(@NonNull @MediaDrm.StringProperty String propertyName,
                                     @NonNull String value)
            throws NoDrmSchemeException
    {
        Log.v(TAG, "setDrmPropertyString: propertyName: " + propertyName + " value: " + value);

        synchronized (mDrmLock) {

            if ( !mActiveDrmScheme && !mDrmConfigAllowed ) {
                Log.w(TAG, "setDrmPropertyString NoDrmSchemeException");
                throw new NoDrmSchemeException("setDrmPropertyString: Has to prepareDrm() first.");
            }

            try {
                mDrmObj.setPropertyString(propertyName, value);
            } catch ( Exception e ) {
                Log.w(TAG, "setDrmPropertyString Exception " + e);
                throw e;
            }
        }   // synchronized
    }

    /**
     * Encapsulates the DRM properties of the source.
     */
    public static final class DrmInfo {
        private Map<UUID, byte[]> mapPssh;
        private UUID[] supportedSchemes;

        /**
         * Returns the PSSH info of the data source for each supported DRM scheme.
         */
        public Map<UUID, byte[]> getPssh() {
            return mapPssh;
        }

        /**
         * Returns the intersection of the data source and the device DRM schemes.
         * It effectively identifies the subset of the source's DRM schemes which
         * are supported by the device too.
         */
        public UUID[] getSupportedSchemes() {
            return supportedSchemes;
        }

        private DrmInfo(Map<UUID, byte[]> Pssh, UUID[] SupportedSchemes) {
            mapPssh = Pssh;
            supportedSchemes = SupportedSchemes;
        }

        private DrmInfo(Parcel parcel) {
            Log.v(TAG, "DrmInfo(" + parcel + ") size " + parcel.dataSize());

            int psshsize = parcel.readInt();
            byte[] pssh = new byte[psshsize];
            parcel.readByteArray(pssh);

            Log.v(TAG, "DrmInfo() PSSH: " + arrToHex(pssh));
            mapPssh = parsePSSH(pssh, psshsize);
            Log.v(TAG, "DrmInfo() PSSH: " + mapPssh);

            int supportedDRMsCount = parcel.readInt();
            supportedSchemes = new UUID[supportedDRMsCount];
            for (int i = 0; i < supportedDRMsCount; i++) {
                byte[] uuid = new byte[16];
                parcel.readByteArray(uuid);

                supportedSchemes[i] = bytesToUUID(uuid);

                Log.v(TAG, "DrmInfo() supportedScheme[" + i + "]: " +
                      supportedSchemes[i]);
            }

            Log.v(TAG, "DrmInfo() Parcel psshsize: " + psshsize +
                  " supportedDRMsCount: " + supportedDRMsCount);
        }

        private DrmInfo makeCopy() {
            return new DrmInfo(this.mapPssh, this.supportedSchemes);
        }

        private String arrToHex(byte[] bytes) {
            String out = "0x";
            for (int i = 0; i < bytes.length; i++) {
                out += String.format("%02x", bytes[i]);
            }

            return out;
        }

        private UUID bytesToUUID(byte[] uuid) {
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb |= ( ((long)uuid[i]   & 0xff) << (8 * (7 - i)) );
                lsb |= ( ((long)uuid[i+8] & 0xff) << (8 * (7 - i)) );
            }

            return new UUID(msb, lsb);
        }

        private Map<UUID, byte[]> parsePSSH(byte[] pssh, int psshsize) {
            Map<UUID, byte[]> result = new HashMap<UUID, byte[]>();

            final int UUID_SIZE = 16;
            final int DATALEN_SIZE = 4;

            int len = psshsize;
            int numentries = 0;
            int i = 0;

            while (len > 0) {
                if (len < UUID_SIZE) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "UUID: (%d < 16) pssh: %d", len, psshsize));
                    return null;
                }

                byte[] subset = Arrays.copyOfRange(pssh, i, i + UUID_SIZE);
                UUID uuid = bytesToUUID(subset);
                i += UUID_SIZE;
                len -= UUID_SIZE;

                // get data length
                if (len < 4) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "datalen: (%d < 4) pssh: %d", len, psshsize));
                    return null;
                }

                subset = Arrays.copyOfRange(pssh, i, i+DATALEN_SIZE);
                int datalen = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ?
                    ((subset[3] & 0xff) << 24) | ((subset[2] & 0xff) << 16) |
                    ((subset[1] & 0xff) <<  8) |  (subset[0] & 0xff)          :
                    ((subset[0] & 0xff) << 24) | ((subset[1] & 0xff) << 16) |
                    ((subset[2] & 0xff) <<  8) |  (subset[3] & 0xff) ;
                i += DATALEN_SIZE;
                len -= DATALEN_SIZE;

                if (len < datalen) {
                    Log.w(TAG, String.format("parsePSSH: len is too short to parse " +
                                             "data: (%d < %d) pssh: %d", len, datalen, psshsize));
                    return null;
                }

                byte[] data = Arrays.copyOfRange(pssh, i, i+datalen);

                // skip the data
                i += datalen;
                len -= datalen;

                Log.v(TAG, String.format("parsePSSH[%d]: <%s, %s> pssh: %d",
                                         numentries, uuid, arrToHex(data), psshsize));
                numentries++;
                result.put(uuid, data);
            }

            return result;
        }

    };  // DrmInfo

    /**
     * Thrown when a DRM method is called before preparing a DRM scheme through prepareDrm().
     * Extends MediaDrm.MediaDrmException
     */
    public static final class NoDrmSchemeException extends MediaDrmException {
        public NoDrmSchemeException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Thrown when the device requires DRM provisioning but the provisioning attempt has
     * failed due to a network error (Internet reachability, timeout, etc.).
     * Extends MediaDrm.MediaDrmException
     */
    public static final class ProvisioningNetworkErrorException extends MediaDrmException {
        public ProvisioningNetworkErrorException(String detailMessage) {
            super(detailMessage);
        }
    }

    /**
     * Thrown when the device requires DRM provisioning but the provisioning attempt has
     * failed due to the provisioning server denying the request.
     * Extends MediaDrm.MediaDrmException
     */
    public static final class ProvisioningServerErrorException extends MediaDrmException {
        public ProvisioningServerErrorException(String detailMessage) {
            super(detailMessage);
        }
    }


    private native void _prepareDrm(@NonNull byte[] uuid, @NonNull byte[] drmSessionId);

        // Modular DRM helpers

    private void prepareDrm_createDrmStep(@NonNull UUID uuid)
            throws UnsupportedSchemeException {
        Log.v(TAG, "prepareDrm_createDrmStep: UUID: " + uuid);

        try {
            mDrmObj = new MediaDrm(uuid);
            Log.v(TAG, "prepareDrm_createDrmStep: Created mDrmObj=" + mDrmObj);
        } catch (Exception e) { // UnsupportedSchemeException
            Log.e(TAG, "prepareDrm_createDrmStep: MediaDrm failed with " + e);
            throw e;
        }
    }

    private void prepareDrm_openSessionStep(@NonNull UUID uuid)
            throws NotProvisionedException, ResourceBusyException {
        Log.v(TAG, "prepareDrm_openSessionStep: uuid: " + uuid);

        // TODO: don't need an open session for a future specialKeyReleaseDrm mode but we should do
        // it anyway so it raises provisioning error if needed. We'd rather handle provisioning
        // at prepareDrm/openSession rather than getKeyRequest/provideKeyResponse
        try {
            mDrmSessionId = mDrmObj.openSession();
            Log.v(TAG, "prepareDrm_openSessionStep: mDrmSessionId=" + mDrmSessionId);

            // Sending it down to native/mediaserver to create the crypto object
            // This call could simply fail due to bad player state, e.g., after start().
            _prepareDrm(getByteArrayFromUUID(uuid), mDrmSessionId);
            Log.v(TAG, "prepareDrm_openSessionStep: _prepareDrm/Crypto succeeded");

        } catch (Exception e) { //ResourceBusyException, NotProvisionedException
            Log.e(TAG, "prepareDrm_openSessionStep: open/crypto failed with " + e);
            throw e;
        }

    }

    private class ProvisioningThread extends Thread
    {
        public static final int TIMEOUT_MS = 60000;

        private UUID uuid;
        private String urlStr;
        private Object drmLock;
        private OnDrmPreparedHandlerDelegate onDrmPreparedHandlerDelegate;
        private MediaPlayer mediaPlayer;
        private int status;
        private boolean finished;
        public  int status() {
            return status;
        }

        public ProvisioningThread initialize(MediaDrm.ProvisionRequest request,
                                          UUID uuid, MediaPlayer mediaPlayer) {
            // lock is held by the caller
            drmLock = mediaPlayer.mDrmLock;
            onDrmPreparedHandlerDelegate = mediaPlayer.mOnDrmPreparedHandlerDelegate;
            this.mediaPlayer = mediaPlayer;

            urlStr = request.getDefaultUrl() + "&signedRequest=" + new String(request.getData());
            this.uuid = uuid;

            status = PREPARE_DRM_STATUS_PREPARATION_ERROR;

            Log.v(TAG, "HandleProvisioninig: Thread is initialised url: " + urlStr);
            return this;
        }

        public void run() {

            byte[] response = null;
            boolean provisioningSucceeded = false;
            try {
                URL url = new URL(urlStr);
                final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(false);
                    connection.setDoInput(true);
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);

                    connection.connect();
                    response = Streams.readFully(connection.getInputStream());

                    Log.v(TAG, "HandleProvisioninig: Thread run: response " +
                            response.length + " " + response);
                } catch (Exception e) {
                    status = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                    Log.w(TAG, "HandleProvisioninig: Thread run: connect " + e + " url: " + url);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e)   {
                status = PREPARE_DRM_STATUS_PROVISIONING_NETWORK_ERROR;
                Log.w(TAG, "HandleProvisioninig: Thread run: openConnection " + e);
            }

            if (response != null) {
                try {
                    mDrmObj.provideProvisionResponse(response);
                    Log.v(TAG, "HandleProvisioninig: Thread run: " +
                            "provideProvisionResponse SUCCEEDED!");

                    provisioningSucceeded = true;
                } catch (Exception e) {
                    status = PREPARE_DRM_STATUS_PROVISIONING_SERVER_ERROR;
                    Log.w(TAG, "HandleProvisioninig: Thread run: " +
                            "provideProvisionResponse " + e);
                }
            }

            boolean succeeded = false;

            // non-blocking mode needs the lock
            if (onDrmPreparedHandlerDelegate != null) {

                synchronized (drmLock) {
                    // continuing with prepareDrm
                    if (provisioningSucceeded) {
                        succeeded = mediaPlayer.resumePrepareDrm(uuid);
                        status = (succeeded) ?
                                PREPARE_DRM_STATUS_SUCCESS :
                                PREPARE_DRM_STATUS_PREPARATION_ERROR;
                    }
                    mediaPlayer.mDrmProvisioningInProgress = false;
                    mediaPlayer.mPrepareDrmInProgress = false;
                    if (!succeeded) {
                        cleanDrmObj();  // cleaning up if it hasn't gone through while in the lock
                    }
                } // synchronized

                // calling the callback outside the lock
                onDrmPreparedHandlerDelegate.notifyClient(status);
            } else {   // blocking mode already has the lock

                // continuing with prepareDrm
                if (provisioningSucceeded) {
                    succeeded = mediaPlayer.resumePrepareDrm(uuid);
                    status = (succeeded) ?
                            PREPARE_DRM_STATUS_SUCCESS :
                            PREPARE_DRM_STATUS_PREPARATION_ERROR;
                }
                mediaPlayer.mDrmProvisioningInProgress = false;
                mediaPlayer.mPrepareDrmInProgress = false;
                if (!succeeded) {
                    cleanDrmObj();  // cleaning up if it hasn't gone through
                }
            }

            finished = true;
        }   // run()

    }   // ProvisioningThread

    private int HandleProvisioninig(UUID uuid)
    {
        // the lock is already held by the caller

        if (mDrmProvisioningInProgress) {
            Log.e(TAG, "HandleProvisioninig: Unexpected mDrmProvisioningInProgress");
            return PREPARE_DRM_STATUS_PREPARATION_ERROR;
        }

        MediaDrm.ProvisionRequest provReq = mDrmObj.getProvisionRequest();
        if (provReq == null) {
            Log.e(TAG, "HandleProvisioninig: getProvisionRequest returned null.");
            return PREPARE_DRM_STATUS_PREPARATION_ERROR;
        }

        Log.v(TAG, "HandleProvisioninig provReq " +
                " data: " + provReq.getData() + " url: " + provReq.getDefaultUrl());

        // networking in a background thread
        mDrmProvisioningInProgress = true;

        mDrmProvisioningThread = new ProvisioningThread().initialize(provReq, uuid, this);
        mDrmProvisioningThread.start();

        int result;

        // non-blocking: this is not the final result
        if (mOnDrmPreparedHandlerDelegate != null) {
            result = PREPARE_DRM_STATUS_SUCCESS;
        } else {
            // if blocking mode, wait till provisioning is done
            try {
                mDrmProvisioningThread.join();
            } catch (Exception e) {
                Log.w(TAG, "HandleProvisioninig: Thread.join Exception " + e);
            }
            result = mDrmProvisioningThread.status();
            // no longer need the thread
            mDrmProvisioningThread = null;
        }

        return result;
    }

    private boolean resumePrepareDrm(UUID uuid)
    {
        Log.v(TAG, "resumePrepareDrm: uuid: " + uuid);

        // mDrmLock is guaranteed to be held
        boolean success = false;
        try {
            // resuming
            prepareDrm_openSessionStep(uuid);

            mDrmUUID = uuid;
            mActiveDrmScheme = true;

            success = true;
        } catch (Exception e) {
            Log.w(TAG, "HandleProvisioninig: Thread run _prepareDrm resume failed with " + e);
            // mDrmObj clean up is done by the caller
        }

        return success;
    }

    private void resetDrmState()
    {
        synchronized (mDrmLock) {
            Log.v(TAG, "resetDrmState: " +
                    " mDrmInfo=" + mDrmInfo +
                    " mDrmProvisioningThread=" + mDrmProvisioningThread +
                    " mPrepareDrmInProgress=" + mPrepareDrmInProgress +
                    " mActiveDrmScheme=" + mActiveDrmScheme);

            mDrmInfoResolved = false;
            mDrmInfo = null;

            if (mDrmProvisioningThread != null) {
                // timeout; relying on HttpUrlConnection
                try {
                    mDrmProvisioningThread.join();
                }
                catch (InterruptedException e) {
                    Log.w(TAG, "resetDrmState: ProvThread.join Exception " + e);
                }
                mDrmProvisioningThread = null;
            }

            mPrepareDrmInProgress = false;
            mActiveDrmScheme = false;

            cleanDrmObj();
        }   // synchronized
    }

    private void cleanDrmObj()
    {
        // the caller holds mDrmLock
        Log.v(TAG, "cleanDrmObj: mDrmObj=" + mDrmObj + " mDrmSessionId=" + mDrmSessionId);

        if (mDrmSessionId != null)    {
            mDrmObj.closeSession(mDrmSessionId);
            mDrmSessionId = null;
        }
        if (mDrmObj != null) {
            mDrmObj.release();
            mDrmObj = null;
        }
    }

    private static final byte[] getByteArrayFromUUID(@NonNull UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        byte[] uuidBytes = new byte[16];
        for (int i = 0; i < 8; ++i) {
            uuidBytes[i] = (byte)(msb >>> (8 * (7 - i)));
            uuidBytes[8 + i] = (byte)(lsb >>> (8 * (7 - i)));
        }

        return uuidBytes;
    }

    // Modular DRM end

    /*
     * Test whether a given video scaling mode is supported.
     */
    private boolean isVideoScalingModeSupported(int mode) {
        return (mode == VIDEO_SCALING_MODE_SCALE_TO_FIT ||
                mode == VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
    }

    /** @hide */
    static class TimeProvider implements MediaPlayer.OnSeekCompleteListener,
            MediaTimeProvider {
        private static final String TAG = "MTP";
        private static final long MAX_NS_WITHOUT_POSITION_CHECK = 5000000000L;
        private static final long MAX_EARLY_CALLBACK_US = 1000;
        private static final long TIME_ADJUSTMENT_RATE = 2;  /* meaning 1/2 */
        private long mLastTimeUs = 0;
        private MediaPlayer mPlayer;
        private boolean mPaused = true;
        private boolean mStopped = true;
        private boolean mBuffering;
        private long mLastReportedTime;
        // since we are expecting only a handful listeners per stream, there is
        // no need for log(N) search performance
        private MediaTimeProvider.OnMediaTimeListener mListeners[];
        private long mTimes[];
        private Handler mEventHandler;
        private boolean mRefresh = false;
        private boolean mPausing = false;
        private boolean mSeeking = false;
        private static final int NOTIFY = 1;
        private static final int NOTIFY_TIME = 0;
        private static final int NOTIFY_STOP = 2;
        private static final int NOTIFY_SEEK = 3;
        private static final int NOTIFY_TRACK_DATA = 4;
        private HandlerThread mHandlerThread;

        /** @hide */
        public boolean DEBUG = false;

        public TimeProvider(MediaPlayer mp) {
            mPlayer = mp;
            try {
                getCurrentTimeUs(true, false);
            } catch (IllegalStateException e) {
                // we assume starting position
                mRefresh = true;
            }

            Looper looper;
            if ((looper = Looper.myLooper()) == null &&
                (looper = Looper.getMainLooper()) == null) {
                // Create our own looper here in case MP was created without one
                mHandlerThread = new HandlerThread("MediaPlayerMTPEventThread",
                      Process.THREAD_PRIORITY_FOREGROUND);
                mHandlerThread.start();
                looper = mHandlerThread.getLooper();
            }
            mEventHandler = new EventHandler(looper);

            mListeners = new MediaTimeProvider.OnMediaTimeListener[0];
            mTimes = new long[0];
            mLastTimeUs = 0;
        }

        private void scheduleNotification(int type, long delayUs) {
            // ignore time notifications until seek is handled
            if (mSeeking && type == NOTIFY_TIME) {
                return;
            }

            if (DEBUG) Log.v(TAG, "scheduleNotification " + type + " in " + delayUs);
            mEventHandler.removeMessages(NOTIFY);
            Message msg = mEventHandler.obtainMessage(NOTIFY, type, 0);
            mEventHandler.sendMessageDelayed(msg, (int) (delayUs / 1000));
        }

        /** @hide */
        public void close() {
            mEventHandler.removeMessages(NOTIFY);
            if (mHandlerThread != null) {
                mHandlerThread.quitSafely();
                mHandlerThread = null;
            }
        }

        /** @hide */
        protected void finalize() {
            if (mHandlerThread != null) {
                mHandlerThread.quitSafely();
            }
        }

        /** @hide */
        public void onNotifyTime() {
            synchronized (this) {
                if (DEBUG) Log.d(TAG, "onNotifyTime: ");
                scheduleNotification(NOTIFY_TIME, 0 /* delay */);
            }
        }

        /** @hide */
        public void onPaused(boolean paused) {
            synchronized(this) {
                if (DEBUG) Log.d(TAG, "onPaused: " + paused);
                if (mStopped) { // handle as seek if we were stopped
                    mStopped = false;
                    mSeeking = true;
                    scheduleNotification(NOTIFY_SEEK, 0 /* delay */);
                } else {
                    mPausing = paused;  // special handling if player disappeared
                    mSeeking = false;
                    scheduleNotification(NOTIFY_TIME, 0 /* delay */);
                }
            }
        }

        /** @hide */
        public void onBuffering(boolean buffering) {
            synchronized (this) {
                if (DEBUG) Log.d(TAG, "onBuffering: " + buffering);
                mBuffering = buffering;
                scheduleNotification(NOTIFY_TIME, 0 /* delay */);
            }
        }

        /** @hide */
        public void onStopped() {
            synchronized(this) {
                if (DEBUG) Log.d(TAG, "onStopped");
                mPaused = true;
                mStopped = true;
                mSeeking = false;
                mBuffering = false;
                scheduleNotification(NOTIFY_STOP, 0 /* delay */);
            }
        }

        /** @hide */
        @Override
        public void onSeekComplete(MediaPlayer mp) {
            synchronized(this) {
                mStopped = false;
                mSeeking = true;
                scheduleNotification(NOTIFY_SEEK, 0 /* delay */);
            }
        }

        /** @hide */
        public void onNewPlayer() {
            if (mRefresh) {
                synchronized(this) {
                    mStopped = false;
                    mSeeking = true;
                    mBuffering = false;
                    scheduleNotification(NOTIFY_SEEK, 0 /* delay */);
                }
            }
        }

        private synchronized void notifySeek() {
            mSeeking = false;
            try {
                long timeUs = getCurrentTimeUs(true, false);
                if (DEBUG) Log.d(TAG, "onSeekComplete at " + timeUs);

                for (MediaTimeProvider.OnMediaTimeListener listener: mListeners) {
                    if (listener == null) {
                        break;
                    }
                    listener.onSeek(timeUs);
                }
            } catch (IllegalStateException e) {
                // we should not be there, but at least signal pause
                if (DEBUG) Log.d(TAG, "onSeekComplete but no player");
                mPausing = true;  // special handling if player disappeared
                notifyTimedEvent(false /* refreshTime */);
            }
        }

        private synchronized void notifyTrackData(Pair<SubtitleTrack, byte[]> trackData) {
            SubtitleTrack track = trackData.first;
            byte[] data = trackData.second;
            track.onData(data, true /* eos */, ~0 /* runID: keep forever */);
        }

        private synchronized void notifyStop() {
            for (MediaTimeProvider.OnMediaTimeListener listener: mListeners) {
                if (listener == null) {
                    break;
                }
                listener.onStop();
            }
        }

        private int registerListener(MediaTimeProvider.OnMediaTimeListener listener) {
            int i = 0;
            for (; i < mListeners.length; i++) {
                if (mListeners[i] == listener || mListeners[i] == null) {
                    break;
                }
            }

            // new listener
            if (i >= mListeners.length) {
                MediaTimeProvider.OnMediaTimeListener[] newListeners =
                    new MediaTimeProvider.OnMediaTimeListener[i + 1];
                long[] newTimes = new long[i + 1];
                System.arraycopy(mListeners, 0, newListeners, 0, mListeners.length);
                System.arraycopy(mTimes, 0, newTimes, 0, mTimes.length);
                mListeners = newListeners;
                mTimes = newTimes;
            }

            if (mListeners[i] == null) {
                mListeners[i] = listener;
                mTimes[i] = MediaTimeProvider.NO_TIME;
            }
            return i;
        }

        public void notifyAt(
                long timeUs, MediaTimeProvider.OnMediaTimeListener listener) {
            synchronized(this) {
                if (DEBUG) Log.d(TAG, "notifyAt " + timeUs);
                mTimes[registerListener(listener)] = timeUs;
                scheduleNotification(NOTIFY_TIME, 0 /* delay */);
            }
        }

        public void scheduleUpdate(MediaTimeProvider.OnMediaTimeListener listener) {
            synchronized(this) {
                if (DEBUG) Log.d(TAG, "scheduleUpdate");
                int i = registerListener(listener);

                if (!mStopped) {
                    mTimes[i] = 0;
                    scheduleNotification(NOTIFY_TIME, 0 /* delay */);
                }
            }
        }

        public void cancelNotifications(
                MediaTimeProvider.OnMediaTimeListener listener) {
            synchronized(this) {
                int i = 0;
                for (; i < mListeners.length; i++) {
                    if (mListeners[i] == listener) {
                        System.arraycopy(mListeners, i + 1,
                                mListeners, i, mListeners.length - i - 1);
                        System.arraycopy(mTimes, i + 1,
                                mTimes, i, mTimes.length - i - 1);
                        mListeners[mListeners.length - 1] = null;
                        mTimes[mTimes.length - 1] = NO_TIME;
                        break;
                    } else if (mListeners[i] == null) {
                        break;
                    }
                }

                scheduleNotification(NOTIFY_TIME, 0 /* delay */);
            }
        }

        private synchronized void notifyTimedEvent(boolean refreshTime) {
            // figure out next callback
            long nowUs;
            try {
                nowUs = getCurrentTimeUs(refreshTime, true);
            } catch (IllegalStateException e) {
                // assume we paused until new player arrives
                mRefresh = true;
                mPausing = true; // this ensures that call succeeds
                nowUs = getCurrentTimeUs(refreshTime, true);
            }
            long nextTimeUs = nowUs;

            if (mSeeking) {
                // skip timed-event notifications until seek is complete
                return;
            }

            if (DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("notifyTimedEvent(").append(mLastTimeUs).append(" -> ")
                        .append(nowUs).append(") from {");
                boolean first = true;
                for (long time: mTimes) {
                    if (time == NO_TIME) {
                        continue;
                    }
                    if (!first) sb.append(", ");
                    sb.append(time);
                    first = false;
                }
                sb.append("}");
                Log.d(TAG, sb.toString());
            }

            Vector<MediaTimeProvider.OnMediaTimeListener> activatedListeners =
                new Vector<MediaTimeProvider.OnMediaTimeListener>();
            for (int ix = 0; ix < mTimes.length; ix++) {
                if (mListeners[ix] == null) {
                    break;
                }
                if (mTimes[ix] <= NO_TIME) {
                    // ignore, unless we were stopped
                } else if (mTimes[ix] <= nowUs + MAX_EARLY_CALLBACK_US) {
                    activatedListeners.add(mListeners[ix]);
                    if (DEBUG) Log.d(TAG, "removed");
                    mTimes[ix] = NO_TIME;
                } else if (nextTimeUs == nowUs || mTimes[ix] < nextTimeUs) {
                    nextTimeUs = mTimes[ix];
                }
            }

            if (nextTimeUs > nowUs && !mPaused) {
                // schedule callback at nextTimeUs
                if (DEBUG) Log.d(TAG, "scheduling for " + nextTimeUs + " and " + nowUs);
                mPlayer.notifyAt(nextTimeUs);
            } else {
                mEventHandler.removeMessages(NOTIFY);
                // no more callbacks
            }

            for (MediaTimeProvider.OnMediaTimeListener listener: activatedListeners) {
                listener.onTimedEvent(nowUs);
            }
        }

        public long getCurrentTimeUs(boolean refreshTime, boolean monotonic)
                throws IllegalStateException {
            synchronized (this) {
                // we always refresh the time when the paused-state changes, because
                // we expect to have received the pause-change event delayed.
                if (mPaused && !refreshTime) {
                    return mLastReportedTime;
                }

                try {
                    mLastTimeUs = mPlayer.getCurrentPosition() * 1000L;
                    mPaused = !mPlayer.isPlaying() || mBuffering;
                    if (DEBUG) Log.v(TAG, (mPaused ? "paused" : "playing") + " at " + mLastTimeUs);
                } catch (IllegalStateException e) {
                    if (mPausing) {
                        // if we were pausing, get last estimated timestamp
                        mPausing = false;
                        if (!monotonic || mLastReportedTime < mLastTimeUs) {
                            mLastReportedTime = mLastTimeUs;
                        }
                        mPaused = true;
                        if (DEBUG) Log.d(TAG, "illegal state, but pausing: estimating at " + mLastReportedTime);
                        return mLastReportedTime;
                    }
                    // TODO get time when prepared
                    throw e;
                }
                if (monotonic && mLastTimeUs < mLastReportedTime) {
                    /* have to adjust time */
                    if (mLastReportedTime - mLastTimeUs > 1000000) {
                        // schedule seeked event if time jumped significantly
                        // TODO: do this properly by introducing an exception
                        mStopped = false;
                        mSeeking = true;
                        scheduleNotification(NOTIFY_SEEK, 0 /* delay */);
                    }
                } else {
                    mLastReportedTime = mLastTimeUs;
                }

                return mLastReportedTime;
            }
        }

        private class EventHandler extends Handler {
            public EventHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                if (msg.what == NOTIFY) {
                    switch (msg.arg1) {
                    case NOTIFY_TIME:
                        notifyTimedEvent(true /* refreshTime */);
                        break;
                    case NOTIFY_STOP:
                        notifyStop();
                        break;
                    case NOTIFY_SEEK:
                        notifySeek();
                        break;
                    case NOTIFY_TRACK_DATA:
                        notifyTrackData((Pair<SubtitleTrack, byte[]>)msg.obj);
                        break;
                    }
                }
            }
        }
    }

    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        /**
         * Key to extract the MIME type of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String MIME_TYPE_VIDEO = "android.media.mediaplayer.video.mime";

        /**
         * Key to extract the codec being used to decode the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String CODEC_VIDEO = "android.media.mediaplayer.video.codec";

        /**
         * Key to extract the width (in pixels) of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String WIDTH = "android.media.mediaplayer.width";

        /**
         * Key to extract the height (in pixels) of the video track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String HEIGHT = "android.media.mediaplayer.height";

        /**
         * Key to extract the count of video frames played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String FRAMES = "android.media.mediaplayer.frames";

        /**
         * Key to extract the count of video frames dropped
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String FRAMES_DROPPED = "android.media.mediaplayer.dropped";

        /**
         * Key to extract the MIME type of the audio track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String MIME_TYPE_AUDIO = "android.media.mediaplayer.audio.mime";

        /**
         * Key to extract the codec being used to decode the audio track
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a String.
         */
        public static final String CODEC_AUDIO = "android.media.mediaplayer.audio.codec";

        /**
         * Key to extract the duration (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a long.
         */
        public static final String DURATION = "android.media.mediaplayer.durationMs";

        /**
         * Key to extract the playing time (in milliseconds) of the
         * media being played
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is a long.
         */
        public static final String PLAYING = "android.media.mediaplayer.playingMs";

        /**
         * Key to extract the count of errors encountered while
         * playing the media
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String ERRORS = "android.media.mediaplayer.err";

        /**
         * Key to extract an (optional) error code detected while
         * playing the media
         * from the {@link MediaPlayer#getMetrics} return value.
         * The value is an integer.
         */
        public static final String ERROR_CODE = "android.media.mediaplayer.errcode";

    }
}

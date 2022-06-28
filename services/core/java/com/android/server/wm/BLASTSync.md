= What does it mean for BLAST Sync to work? =
There are two BLAST sync primitives on the server side, BLASTSyncEngine and applyWithNextDraw.
Both of them are used to solve subclasses of this category of problem:
 1. You have some server side changes, which will trigger both WM/SysUI initiated SurfaceControl.Transactions, 
    and also trigger a client redraw/update
 2. You want to synchronize the redraw of those clients with the application of those WM/SysUI side changes.

Put simply, you would like to synchronize the graphical effects of some WM changes with the graphical output of various windows
observing those changes.

To talk about exactly what the primitives guarantee, we need to clarify what we mean by server side changes. 
In this document we will use a term syncable state to refer to any state mutated under the WindowManager lock
which when observed by the client, produces a visible outcome in the produced frame. 
For example the current Configuration. 

The guarantee provided by Server-side BLAST Sync Primitives is thus:
Guarantee 1:	If you make a set of changes to syncable state, at the same time that you begin a sync, 
then the first frame drawn by the client after observing the syncable state will be sent in a transaction
to the consumer of the sync which was begun, rather than directly sent to SurfaceFlinger.

Here "at the same time" means in the same critical section (while holding the WM lock)
For example this guarantee means that you can write code like:
	window.performConfigurationChange(someConfiguration)
	window.applyOnNextDraw(consumer)
And the consumer will always be passed the first frame containing the configuration change. This can work with any 
syncable state not just Configuration.

The following is the protocol and additional requirements for BLAST sync, and an analysis of why it is correct. Due to time
constraints we analyze it for a single window only (as this is actually the hard part).

Protocol requirements:
    Server protocol, precondition, begin a syncSeqId integer per window at 0:
        SA: Enter the critical section
        SB: Change syncable state, any number of times, prepare any number of syncs (and
            increment the seqId if a sync was prepared, assosciate it with the sync)
        SC: Leave the critical section
        SD: Send syncable state updates to the client, always paired with the current seqId
        SE: When the client calls finishDrawing, execute the consumer for each sync with
            seqId <= the seqId which the client passed to finishDrawing
    Client protocol:
        CA: Observe state and seqid changes up until a fixed frame deadline, then execute performTraversals
        CB: If the seqId is incremeneted at the time of the frame deadline, configure the renderer to
            redirect the next draw in to a transaction, record the seqId at the time
        CC: When the draw is finished, send the transaction containing the draw to WM with the
            previously recorded seqId
    Additional requirements/assumptions:
        1. The server may only send changes to the syncable state paired with the seqId. The client may
           only receive them together (e.g. not from other sources)
        2. In between changing and sending syncable state, the lock must be released and acquired again
        3. The client wont draw a frame reflecting syncable state changes without passing through "performTraversals"
        4. Drawing never fails
        5. There are no blocking calls between the client or the server
            
Note that the server can begin the protocol at any time, so it may be possible for the client to proceed through
phases SA, SB, SC, and SD multiple times before the client receives any messages.

To show that the guarantee can't be violated, we use a notation of sequences, where we describe interleaving
of protocol events. For duplicate events, we attach a number, e.g. SA_1, SA_2.

We proceed by contradiction, imagine there was some sequence (..., SA_N, ...) for which the guarantee was
not upheld. This means that either
    1. finishDrawing with the assosciate seqId was never sent to the server OR
    2. It was sent too late (after the first frame was sent to SF instead of WM) OR
    3. It was sent too early (not containing the state changes originating with SA_N)
If it was sent neither too late, nor too early, and contained the assosciated seqId, then protocol step SE
says that the frame will be passed to the consumer and we uphold our guarantee.

The first case is impossible because step SD says that the server always sends the seqId if a sync was prepared.
If we send it the client must receive it. Since we only increment the seqId, and the client only takes the
seqId from us (requirement 1, protocol step SB), the received ID must be higher than the clients previous seqId.
CA says that performTraversals will execute, and CB says that when it does, if the seqId is higher than before
it will schedule the render to sync. Requirement 4 says drawing never fails, so CC must execute, and so we will always
eventually send every seqId (or a seqId > than it) back to the server.

It also can't be sent too late. By requirement 2 we must release and acquire the lock
after after changing and before emitting syncable state changes. This means it's guaranteed
that even in an ordering like AcquireLock, ChangeState, PrepareSync, Release lock we can't
send the state changes before prepareSync, and so they can always include at least the seqId
assosciated with changestate (or a later one).
Since we only receive the SeqId with the State changes (requirement 1),
and we wont draw state changes without passing through perform traversals (requirement 3) the first frame
containing the state change must have been generated by a call to performTraversals which also observed
the seqId change, and so it will appropriately configure the renderer.

By the same argument it can't be sent too early! Since we only send seqIds we receive from the server, 
and we only send seqIds after completing a drawing pass of the assosciated state.

So we can see that no matter at what time the server makes syncable state changes, the first frame will
always be delivered to the draw handler. Assuming that the client and server uphold this protocol and these
requirements.

The trickiest part of the implementation at the moment is due to assosciating seqId. Currently we send one of the most
most important pieces of syncable state (configuration) over multiple channels. Namely ClientTransaction
and MSG_RESIZED. The ordering of these relative to sync preparation in server code is undefined, in fact we have cases like
this all the time:
    acquireLock()
    changeConfiguration()
    // time passes, but still in critical section
    prepareSync()
    releaseLock()
This is exactly the kind of case Guarantee 1 mentions as an example. In previous incarnations of the code this worked
because relayoutWindow needed to acquire the same lock and relayoutWindow was a necessary part of completing sync.

Now that we have no barrier, that could create issues, because at the time we change configuration (and send the change
to the client via ClientTransaction), we haven't even incremented the seqId yet, and so how can the client observe it
at the same time as the state? We solve this by pushing all client communication through a handler thread that has to
acquire the lock. This ensures we uphold requirement 2.
    
= Transaction ordering =

Applying transactions from different process, and in to different server side transaction queues
raises various questions about transaction ordering. There are two tricky questions in this
domain that we address here:
    1. The ordering of Transactions from a single BLASTBufferQueue wrt to eachother
    2. The ordering of non synced WM updates to syncable state, wrt a BLASTSyncEngine
       transaction
       
== Ordering of Transactions in a single BBQ ==

We can see if sync is never involved, there are never any real questions about ordering.
Even using one-way setTransactionState, the calls are from a single thread to a single
interface on another, and will be ordered. When we hand out transactions for sync
is where issues can start to arise. Obviously if we apply another transaction
immediately after handing out the sync transaction it could arrive first, and this would
cause an ordering issue. It's also possible that the sync transaction arrives before the
transaction applied before it (since setTransactionState is one way from BBQ). Even if
the transactions are applied in the right order, it's possible for them to be
commited out of sync, as the BBQ and SyncConsumer may be using different apply tokens
resulting in the transaction being placed in different server side queues.

To solve these issues, we use a scheme involving "barrier transactions". We show how
this scheme handles every permutation of (Sync, NotSync).

1. NotSync, NotSync: This is the trivial case. As long as both buffers are submitted from
   the same thread, they will arrive in SF in order, and SF will place them in the same
   queue (since there is a shared apply token), which it will process in order.
2. Sync, NotSync: For sync transactions we register a commit callback, and require it to
   be fired before applying the next transaction. Since the commit callback is only
   fired when the transaction has latched on the server side, any transaction applied
   later, must occur later.
3. Sync, Sync: Ordering of consecutive sync transactions is delegated to the sync
    consumer
4. NotSync, Sync: This is the trickiest case, as we don't want to set a commit callback
   on not sync transactions (as this would incur an unacceptable performance overhead).
   Further complicating matters, we want to apply them with one-way binder, since
   this is the hot path for all graphical updates. To solve this we use a
   "setBufferHasBarrier" system. Sync transactions (before they are handed out)
   are tagged with a barrier, referring to the frame number of the last
   non sync transaction. SurfaceFlinger will ensure the correct ordering
   by stalling transactions in the queue until barriers are fulfilled. This barrier
   primitive is dangerous, because it could result in deadlocking, but its ok in
   this scenario since only BBQ uses its apply token.
   
We can see from this that all frames are in order.

== Ordering of WM updates to syncable state ==

A second interesting question, is about the ordering of WM updates to syncable-state. In
the WM we frequently write code like
    getPendingTransaction/getSyncTransaction().show(mSurfaceControl).
In normal operation, getSyncTransaction and getPendingTransaction both refer to the same
transaction which is the displaycontent pendingtransaction, applied by the WindowManager.
During sync, each syncing container will instead use its mSyncTransaction, which will
eventually be merged to be applied by the WindowManager. We can see that we have a congruent
"problem" to BLASTBufferQueue transaction ordering. Namely, earlier state updates which were
given to SysUI could end up being applied later than state updates which were made later but
directly applied by the WM. We can break this down in to two cases:
=== getPendingTransaction() and getSyncTransaction() ordering ===
It's possible for code like this to occur:
getSyncTransaction().OP1
getPendingTransaction().OP2
applyPendingTransaction
applySyncTransaction

in this case the change in OP2 was made later, but occurs first. We define this case as
intended behavior, and say there is no ordering guarantee between the pending
and sync transactions. This means the pending transaction is only useful in
some marginal cases and should probably be considered a deprecated primitive.
=== getSyncTransaction() and getSyncTransaction() ordering ===
However, we take great care to ensure usage of getSyncTransaction() reflects
the ordering the developer would expect. BLASTSyncEngine will register
a commit callback on all transactions it hands out. In the interval between
receiving this commit callback and sending out the transaction, we may have other
data enter getSyncTransaction. If we returned the pending transaction
(as we do in normal time), then we could create ordering issues, since the pending
transactions ordering is undefined. Instead we continue to return the sync transaction
during this interval. If no second sync has started by the time we receive
the commit callback, then we directly apply this left over data in the sync transaction
guaranteed it will be ordered correctly, and return to using the pending
transaction. If a second sync has started, then we just allow the data
to persist in the mSyncTransaction, potentially to be overwritten
by the new sync. It will eventually apply with SysUI's apply token and
ordering will be maintained.

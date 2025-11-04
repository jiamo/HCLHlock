/*
 * HCLHLock.java
 *
 * Created on April 13, 2006, 9:28 PM
 *
 * From "Multiprocessor Synchronization and Concurrent Data Structures",
 * by Maurice Herlihy and Nir Shavit.
 * Copyright 2006 Elsevier Inc. All rights reserved.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * Hierarchical CLH Lock
 *
 * @author Maurice Herlihy
 */
public class HCLHLock implements Lock {
    /**
     * Max number of clusters
     * When debug We can set to 1
     */
    static final int MAX_CLUSTERS = 8;
    /**
     * List of local queues, one per cluster
     */
    List<AtomicReference<QNode>> localQueues;
    /**
     * single global queue
     */
    AtomicReference<QNode> globalQueue;
    /**
     * My current QNode
     */
    ThreadLocal<QNode> currNode = new ThreadLocal<QNode>() {
        protected QNode initialValue() {
            return new QNode();
        };
    };
    ThreadLocal<QNode> preNode = new ThreadLocal<QNode>() {
        protected QNode initialValue() {
            return null;
        };
    };

    /**
     * Creates a new instance of HCLHLock
     */
    public HCLHLock() {
        localQueues = new ArrayList<AtomicReference<QNode>>(MAX_CLUSTERS);
        for (int i = 0; i < MAX_CLUSTERS; i++) {
            localQueues.add(new AtomicReference<QNode>());
        }
        QNode head = new QNode();
        globalQueue = new AtomicReference<QNode>(head);
    }

    public void lock() {
        QNode myLocalNode = currNode.get();
        myLocalNode.setSuccessorMustWait(true);
        myLocalNode.setTailWhenSpliced(false);
        int myCluster = ThreadID.getCluster(MAX_CLUSTERS);
        myLocalNode.setClusterID(myCluster);
        AtomicReference<QNode> localQueue = localQueues.get(myCluster);
        // splice my QNode into local queue
        QNode my_pred;
        QNode local_tail;
        do {
            my_pred = localQueue.get();
        } while (!localQueue.compareAndSet(my_pred, myLocalNode));

        if (my_pred != null) {
            boolean iOwnLock = my_pred.waitForGrantOrClusterMaster(myCluster);
            if (iOwnLock) {
                // I have the lock. Save QNode just released by previous leader
                preNode.set(my_pred);
                return;
            }
            // I am the new cluster master. The TWS signal from my predecessor
            // must be consumed to prevent a thundering herd.
             my_pred.setTailWhenSpliced(false);
        }

        //  https://people.csail.mit.edu/shanir/publications/CLH.pdf
        //  At this point, I’m cluster master.

        // Atomically claim the local queue for splicing. This prevents the race
        // condition where the local queue is modified between the combining delay
        // and the splice operation.
        local_tail = localQueue.getAndSet(null);

        // If the local queue was empty when we claimed it, it means another master
        // already spliced the queue I was in. I should just wait for my local predecessor.
        if (local_tail == null) {
            // My local predecessor is still stored in the my_pred variable from
            // the initial CAS to join the local queue.
            while(my_pred.isSuccessorMustWait()) {
                Thread.yield(); // Yield CPU while waiting
            }
            preNode.set(my_pred);
            return; // I have acquired the lock.
        }

        // I successfully claimed the local queue. Now, splice it to the global queue.
        do {
            my_pred = globalQueue.get();
        } while (!globalQueue.compareAndSet(my_pred, local_tail));

        if (MAX_CLUSTERS > 1) {
            local_tail.setTailWhenSpliced(true);
        }
        while (my_pred.isSuccessorMustWait()) {
            Thread.yield(); // Yield CPU while waiting
        }
        preNode.set(my_pred);
    }

    public void unlock() {
        QNode myNode = currNode.get();
        myNode.setSuccessorMustWait(false);
        currNode.set(preNode.get());
    }

    static class QNode {
        // private boolean tailWhenSpliced;
        private static final int TWS_MASK = 0x80000000;
        // private boolean successorMustWait= false;
        private static final int SMW_MASK = 0x40000000;
        // private int clusterID;
        private static final int CLUSTER_MASK = 0x3FFFFFFF;
        AtomicInteger state;

        public QNode() {
            state = new AtomicInteger(0);
        }

        boolean waitForGrantOrClusterMaster(int myCluster) {

            while (true) {
                if (getClusterID() == myCluster && !isTailWhenSpliced() && !isSuccessorMustWait()) {
                    return true;

                } else if (getClusterID() != myCluster || isTailWhenSpliced()) {
                    return false;
                }
                Thread.yield(); // Yield CPU while waiting
            }
        }

        public void unlock() {
            int oldState = 0;
            int newState = ThreadID.getCluster(MAX_CLUSTERS) & CLUSTER_MASK;
            // successorMustWait = true;
            newState |= SMW_MASK;
            // tailWhenSpliced = false;
            newState &= (~TWS_MASK);
            do {
                oldState = state.get();
            } while (!state.compareAndSet(oldState, newState));
        }

        public int getClusterID() {
            return state.get() & CLUSTER_MASK;
        }

        public void setClusterID(int clusterID) {
            int oldState, newState;
            do {
                oldState = state.get();
                newState = (oldState & ~CLUSTER_MASK) | clusterID;
            } while (!state.compareAndSet(oldState, newState));
        }

        public boolean isSuccessorMustWait() {
            return (state.get() & SMW_MASK) != 0;
        }

        public void setSuccessorMustWait(boolean successorMustWait) {
            int oldState, newState;
            do {
                oldState = state.get();
                if (successorMustWait) {
                    newState = oldState | SMW_MASK;
                } else {
                    newState = oldState & ~SMW_MASK;
                }
            } while (!state.compareAndSet(oldState, newState));
        }

        public boolean isTailWhenSpliced() {
            return (state.get() & TWS_MASK) != 0;
        }

        public void setTailWhenSpliced(boolean tailWhenSpliced) {
            int oldState, newState;
            do {
                oldState = state.get();
                if (tailWhenSpliced) {
                    newState = oldState | TWS_MASK;
                } else {
                    newState = oldState & ~TWS_MASK;
                }
            } while (!state.compareAndSet(oldState, newState));
        }

    }
    public QNode replaceQueueTail(AtomicReference<QNode> queue, QNode node) {
        QNode tail = null;
        do {
            tail = queue.get();
        } while(!queue.compareAndSet(tail, node));

        return tail;
    }

    // superfluous declarations needed to satisfy lock interface
    public void lockInterruptibly() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    public boolean tryLock() {
        throw new UnsupportedOperationException();
    }

    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

}

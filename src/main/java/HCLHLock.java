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
    static final int MAX_CLUSTERS = 1;
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
        int myCluster = ThreadID.getCluster(MAX_CLUSTERS);
        myLocalNode.setClusterID(myCluster);
        AtomicReference<QNode> localQueue = localQueues.get(myCluster);
        // splice my QNode into local queue
        QNode my_pred = null;
        QNode local_tail = null;
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
        }
        // http://www.cs.tau.ac.il/~shanir/nir-pubs-web/Papers/CLH.pdf
        // // At this point, I’m cluster master. Give others time to show up. combining_delay();
        try {
            Thread.sleep(1);
        }catch (Exception e){
        }

        // Splice local queue into global queue.
        // inform successor it is the new master
        do {
            my_pred = globalQueue.get();
            local_tail = localQueue.get();
        } while (!globalQueue.compareAndSet(my_pred, local_tail));

        // here is local Node
        local_tail.setTailWhenSpliced(true);
        // wait for predecessor to release lock
        // I have the lock. Save QNode just released by previous leader
        // Global must come from local
        while (my_pred.isSuccessorMustWait()) {}
        preNode.set(my_pred);
    }

    public void unlock() {

        QNode myNode = currNode.get();
//        int myCluster = ThreadID.getCluster(MAX_CLUSTERS);
//        myNode.setClusterID(myCluster);
        myNode.setSuccessorMustWait(false);
        QNode myPred = preNode.get();
        if (myPred != null){
            myPred.unlock();
            currNode.set(myPred);
        }
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

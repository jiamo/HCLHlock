import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class TestHCLHLock {
    static final int MAX_CLUSTERS = 8;
    public static class ThreadID {
        private static volatile int nextID = 0;
        private static ThreadLocalID threadID = new ThreadLocalID();
        public static int get() { return threadID.get();}
        public static void reset() { nextID = 0; }
        public static void set(int value) { threadID.set(value);}
        public static int getCluster(int n) {return threadID.get() % n;}
        private static class ThreadLocalID extends ThreadLocal<Integer> {
            protected synchronized Integer initialValue() { return nextID++; }}}

    static class QNode {
        private static final int TWS_MASK = 0x80000000;
        private static final int SMW_MASK = 0x40000000;
        private static final int CLUSTER_MASK = 0x3FFFFFFF;
        AtomicInteger state;
        public QNode() { state = new AtomicInteger(0); }

        boolean waitForGrantOrClusterMaster(int myCluster) {
            while (true) {
                if (getClusterID() == myCluster && !isTailWhenSpliced() && !isSuccessorMustWait()) {
                    return true;
                } else if (getClusterID() != myCluster || isTailWhenSpliced()) {
                    return false;
                } } }

        public void unlock() {
            int oldState = 0;
            int newState = ThreadID.getCluster(MAX_CLUSTERS) & CLUSTER_MASK;
            newState |= SMW_MASK;
            newState &= (~TWS_MASK);
            do {
                oldState = state.get();
            } while (!state.compareAndSet(oldState, newState)); }

        public int getClusterID() { return state.get() & CLUSTER_MASK; }

        public void setClusterID(int clusterID) {
            int oldState, newState;
            do {
                oldState = state.get();
                newState = (oldState & ~CLUSTER_MASK) | clusterID;
            } while (!state.compareAndSet(oldState, newState)); }

        public boolean isSuccessorMustWait() { return (state.get() & SMW_MASK) != 0; }

        public void setSuccessorMustWait(boolean successorMustWait) {
            int oldState, newState;
            do {
                oldState = state.get();
                if (successorMustWait) {
                    newState = oldState | SMW_MASK;
                } else {
                    newState = oldState & ~SMW_MASK;
                }
            } while (!state.compareAndSet(oldState, newState)); }

        public boolean isTailWhenSpliced() { return (state.get() & TWS_MASK) != 0; }

        public void setTailWhenSpliced(boolean tailWhenSpliced) {
            int oldState, newState;
            do {
                oldState = state.get();
                if (tailWhenSpliced) {
                    newState = oldState | TWS_MASK;
                } else {
                    newState = oldState & ~TWS_MASK;
                }
            } while (!state.compareAndSet(oldState, newState)); }}

    public class HCLHLock implements Lock {

        List<AtomicReference<QNode>> localQueues;
        AtomicReference<QNode> globalQueue;

        ThreadLocal<QNode> currNode = new ThreadLocal<QNode>() {protected QNode initialValue() { return new QNode(); };};
        ThreadLocal<QNode> preNode = new ThreadLocal<QNode>() {protected QNode initialValue() { return null; };};

        public HCLHLock() {
            localQueues = new ArrayList<AtomicReference<QNode>>(MAX_CLUSTERS);
            for (int i = 0; i < MAX_CLUSTERS; i++) {
                localQueues.add(new AtomicReference<QNode>());
            }
            QNode head = new QNode();
            globalQueue = new AtomicReference<QNode>(head); }

        public void lock() {
            QNode myLocalNode = currNode.get();
            int myCluster = ThreadID.getCluster(MAX_CLUSTERS);
            myLocalNode.setClusterID(myCluster);
            AtomicReference<QNode> localQueue = localQueues.get(ThreadID.getCluster(MAX_CLUSTERS));
            QNode myLocalPred = null;
            QNode myGlobalPred = null;
            QNode localTail = null;
            do {
                myLocalPred = localQueue.get();
            } while (!localQueue.compareAndSet(myLocalPred, myLocalNode));

            if (myLocalPred != null) {
                boolean iOwnLock = myLocalPred.waitForGrantOrClusterMaster(myCluster);
                preNode.set(myLocalPred);
                if (iOwnLock) { return; }
            }
            do {
                myGlobalPred = globalQueue.get();
                localTail = localQueue.get();
            } while (!globalQueue.compareAndSet(myGlobalPred, localTail));

            localTail.setTailWhenSpliced(true);
            while (myGlobalPred.isSuccessorMustWait()) {
            }
            preNode.set(myGlobalPred); }

        public void unlock() {
            QNode myNode = currNode.get();
            myNode.setSuccessorMustWait(false);
            QNode myPred= preNode.get();
            if (myPred != null){
                myPred.unlock();
                currNode.set(myPred); } }

        public void lockInterruptibly() throws InterruptedException { throw new UnsupportedOperationException(); }
        public boolean tryLock() { throw new UnsupportedOperationException(); }
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException { throw new UnsupportedOperationException(); }
        public Condition newCondition() { throw new UnsupportedOperationException(); }
    }

    private final static int THREADS = 32;
    private final static int COUNT = 32 * 64;
    private final static int PER_THREAD = COUNT / THREADS;
    int counter = 0;
    HCLHLock instance = new HCLHLock();

    public  class MyThread extends Thread {
        public void run() {
            for (int i = 0; i < PER_THREAD; i++) {
                instance.lock();
                try {
                    counter = counter + 1;
                } finally {
                    instance.unlock();
                }}}}

    public  void test ()  throws Exception{
        Thread[] thread = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            thread[i] = new MyThread();
        }
        for (int i = 0; i < THREADS; i++) {
            thread[i].start();
        }
        for (int i = 0; i < THREADS; i++) {
            thread[i].join();
        }
        System.out.println(String.format("expect %d, but %d", 2048, counter));
    }

    public static void main(String[] args) throws Exception{
        TestHCLHLock test = new TestHCLHLock();
        test.test(); }
}
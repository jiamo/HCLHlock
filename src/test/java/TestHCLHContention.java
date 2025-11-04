import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public class TestHCLHContention extends TestCase {
    private static final int THREADS = 32;
    // Increase operations to make the test run long enough to measure accurately
    private static final int PER_THREAD_OPERATIONS = 1024;
    private static final int PADDING_SIZE = 16; // Number of longs for padding

    private HCLHLock instance;
    // Use a flat array and strides to guarantee memory layout and prevent false sharing
    private long[] paddedBuffers;
    private static final int CLUSTER_STRIDE = 1 + PADDING_SIZE; // 1 for data, 16 for padding

    private Thread[] threads;

    public TestHCLHContention(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestHCLHContention.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        instance = new HCLHLock();
        paddedBuffers = new long[HCLHLock.MAX_CLUSTERS * CLUSTER_STRIDE];
        threads = new Thread[THREADS];
    }

    public void testLockContention() throws Exception {
        System.out.printf("%nStarting Lock Contention Test with MAX_CLUSTERS = %d...%n", HCLHLock.MAX_CLUSTERS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            threads[i] = new MyThread();
            threads[i].start();
        }

        for (int i = 0; i < THREADS; i++) {
            threads[i].join();
        }

        long endTime = System.currentTimeMillis();
        System.out.printf("Lock Contention Test with MAX_CLUSTERS = %d finished in %d ms.%n%n", HCLHLock.MAX_CLUSTERS, (endTime - startTime));

        // Verification
        int threadsPerCluster = THREADS / HCLHLock.MAX_CLUSTERS;
        long expectedValue = (long) threadsPerCluster * PER_THREAD_OPERATIONS;

        for (int i = 0; i < HCLHLock.MAX_CLUSTERS; i++) {
            assertEquals(
                String.format("Counter for cluster %d is incorrect.", i),
                expectedValue,
                paddedBuffers[i * CLUSTER_STRIDE]
            );
        }
    }

    class MyThread extends Thread {
        @Override
        public void run() {
            int clusterId = ThreadID.getCluster(HCLHLock.MAX_CLUSTERS);
            int bufferIndex = clusterId * CLUSTER_STRIDE;
            for (int i = 0; i < PER_THREAD_OPERATIONS; i++) {
                instance.lock();
                try {
                    // Minimal work in the critical section to maximize lock contention
                    paddedBuffers[bufferIndex]++;
                } finally {
                    instance.unlock();
                }
            }
        }
    }
}

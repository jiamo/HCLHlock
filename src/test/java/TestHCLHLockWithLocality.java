import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public class TestHCLHLockWithLocality extends TestCase {
    private static final int THREADS = 32;
    private static final int COUNT = 32 * 128;
    private static final int PER_THREAD = COUNT / THREADS;

    private HCLHLock instance;
    private long[] counters;
    private Thread[] threads;

    public TestHCLHLockWithLocality(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestHCLHLockWithLocality.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        instance = new HCLHLock();
        counters = new long[HCLHLock.MAX_CLUSTERS];
        threads = new Thread[THREADS];
    }

    public void testParallelWithLocality() throws Exception {
        System.out.printf("Starting test with MAX_CLUSTERS = %d...%n", HCLHLock.MAX_CLUSTERS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            threads[i] = new MyThreadWithLocality();
            threads[i].start();
        }

        for (int i = 0; i < THREADS; i++) {
            threads[i].join();
        }

        long endTime = System.currentTimeMillis();
        System.out.printf("Test with MAX_CLUSTERS = %d finished in %d ms.%n", HCLHLock.MAX_CLUSTERS, (endTime - startTime));

        // Verification
        int threadsPerCluster = THREADS / HCLHLock.MAX_CLUSTERS;
        long expectedCount = (long) threadsPerCluster * PER_THREAD * 100;

        for (int i = 0; i < HCLHLock.MAX_CLUSTERS; i++) {
            assertEquals("Counter for cluster " + i + " is incorrect.", expectedCount, counters[i]);
        }
    }

    class MyThreadWithLocality extends Thread {
        @Override
        public void run() {
            int clusterId = ThreadID.getCluster(HCLHLock.MAX_CLUSTERS);
            for (int i = 0; i < PER_THREAD; i++) {
                instance.lock();
                try {
                    for (int k = 0; k < 100; k++) {
                        counters[clusterId]++;
                    }
                } finally {
                    instance.unlock();
                }
            }
        }
    }
}

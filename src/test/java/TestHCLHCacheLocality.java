import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public class TestHCLHCacheLocality extends TestCase {
    private static final int THREADS = 32;
    private static final int PER_THREAD_OPERATIONS = 1024;
    private static final int BUFFER_SIZE = 16; // Number of longs in the buffer
    private static final int PADDING_SIZE = 16; // Number of longs for padding, ensuring > 64 bytes
    private static final int CLUSTER_BUFFER_STRIDE = BUFFER_SIZE + PADDING_SIZE;

    private HCLHLock instance;
    private long[][] clusterBuffers; // This is now a "padded" 2D array
    private Thread[] threads;

    public TestHCLHCacheLocality(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(TestHCLHCacheLocality.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        instance = new HCLHLock();
        // Allocate a single flat array to control memory layout precisely
        long[] flatBuffer = new long[HCLHLock.MAX_CLUSTERS * CLUSTER_BUFFER_STRIDE];
        // Create a "view" over the flat array to simulate a 2D array with padding
        clusterBuffers = new long[HCLHLock.MAX_CLUSTERS][];
        for (int i = 0; i < HCLHLock.MAX_CLUSTERS; i++) {
            clusterBuffers[i] = new long[BUFFER_SIZE];
            // This setup doesn't actually use the flat buffer, but the concept is key.
            // For a real-world scenario, one would use Unsafe or other techniques.
            // For this test, we'll just allocate separate arrays, which the JVM
            // is likely to place far enough apart in memory.
            // The key is the concept, but for a simple test, this is sufficient.
        }
        threads = new Thread[THREADS];
    }

    public void testParallelWithHighCacheLocality() throws Exception {
        // Re-declare the buffers with explicit padding to be certain.
        // The previous setUp method is not sufficient to guarantee memory separation.
        clusterBuffers = new long[HCLHLock.MAX_CLUSTERS][CLUSTER_BUFFER_STRIDE];


        System.out.printf("%nStarting Padded Cache Locality Test with MAX_CLUSTERS = %d...%n", HCLHLock.MAX_CLUSTERS);
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            threads[i] = new MyThreadWithHighContention();
            threads[i].start();
        }

        for (int i = 0; i < THREADS; i++) {
            threads[i].join();
        }

        long endTime = System.currentTimeMillis();
        System.out.printf("Padded Cache Locality Test with MAX_CLUSTERS = %d finished in %d ms.%n%n", HCLHLock.MAX_CLUSTERS, (endTime - startTime));

        // Verification
        int threadsPerCluster = THREADS / HCLHLock.MAX_CLUSTERS;
        long expectedValue = (long) threadsPerCluster * PER_THREAD_OPERATIONS;

        for (int i = 0; i < HCLHLock.MAX_CLUSTERS; i++) {
            for (int j = 0; j < BUFFER_SIZE; j++) {
                assertEquals(
                    String.format("Buffer value at cluster %d, index %d is incorrect.", i, j),
                    expectedValue,
                    clusterBuffers[i][j]
                );
            }
        }
    }

    class MyThreadWithHighContention extends Thread {
        @Override
        public void run() {
            int clusterId = ThreadID.getCluster(HCLHLock.MAX_CLUSTERS);
            for (int i = 0; i < PER_THREAD_OPERATIONS; i++) {
                instance.lock();
                try {
                    // This workload heavily benefits from cache locality.
                    // All threads in a cluster repeatedly access the same small buffer.
                    long[] localBuffer = clusterBuffers[clusterId];
                    for (int k = 0; k < BUFFER_SIZE; k++) {
                        localBuffer[k]++;
                    }
                } finally {
                    instance.unlock();
                }
            }
        }
    }
}

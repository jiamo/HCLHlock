/*
 * ThreadID.java
 *
 * Created on November 7, 2006, 5:27 PM
 *
 * From "The Art of Multiprocessor Programming",
 * by Maurice Herlihy and Nir Shavit.
 * Copyright 2006 Elsevier Inc. All rights reserved.
 */

/**
 * Illustrates use of thread-local storage. Test by running main().
 *
 * @author Maurice Herlihy
 */
public class ThreadID {
    /**
     * The next thread ID to be assigned
     **/
    private static volatile int nextID = 0;
    /**
     * My thread-local ID.
     **/
    private static ThreadLocalID threadID = new ThreadLocalID();  // this is for thread local

    public static int get() {
        return threadID.get();
    }

    /**
     * When running multiple tests, reset thread id state
     **/
    public static void reset() {
        nextID = 0;
    }

    public static void set(int value) {
        threadID.set(value);
    }

    public static int getCluster(int total_cluster) {
        int tn = threadID.get();
        return tn % total_cluster;
    }

    private static class ThreadLocalID extends ThreadLocal<Integer> {
        protected synchronized Integer initialValue() {
            return nextID++;
        }
    }
}

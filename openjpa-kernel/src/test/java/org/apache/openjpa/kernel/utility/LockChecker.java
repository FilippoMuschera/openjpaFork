package org.apache.openjpa.kernel.utility;

import org.apache.openjpa.util.CacheMap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LockChecker {


    public static boolean tryLock(CacheMap cacheMap) throws InterruptedException {

        CountDownLatch countDownLatch  = new CountDownLatch(1);
        Thread lockThread = new Thread(() -> {
            cacheMap.writeLock();
            countDownLatch.countDown();
            cacheMap.writeUnlock();
        });
        try {
            lockThread.start();
            return countDownLatch.await(1, TimeUnit.SECONDS);

        } finally {
            lockThread.interrupt();
        }



    }

}

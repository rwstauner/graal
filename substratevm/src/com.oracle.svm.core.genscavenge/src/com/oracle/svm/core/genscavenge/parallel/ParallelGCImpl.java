package com.oracle.svm.core.genscavenge.parallel;

import com.oracle.svm.core.SubstrateGCOptions;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk;
import com.oracle.svm.core.genscavenge.GCImpl;
import com.oracle.svm.core.genscavenge.GreyToBlackObjectVisitor;
import com.oracle.svm.core.genscavenge.HeapParameters;
import com.oracle.svm.core.genscavenge.UnalignedHeapChunk;
import com.oracle.svm.core.heap.ParallelGC;
import com.oracle.svm.core.jdk.Jvm;
import com.oracle.svm.core.locks.VMCondition;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.core.util.VMError;
import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import java.util.concurrent.atomic.AtomicInteger;

public class ParallelGCImpl extends ParallelGC {

    public static final int UNALIGNED_BIT = 0x01;
    private static final int MAX_WORKERS_COUNT = 31;

    /**
     * Number of parallel GC workers. Default is 1 so that if GC happens before worker threads are started,
     * it occurs on a single thread just like serial GC.
     */
    private int workerCount = 1;
    private int allWorkersBusy;
    private final AtomicInteger busyWorkers = new AtomicInteger(0);

    /**
     * Each GC worker allocates memory in its own thread local chunk, entering mutex only when new chunk needs to be allocated.
     */
    private static final FastThreadLocalWord<AlignedHeapChunk.AlignedHeader> allocChunkTL =
            FastThreadLocalFactory.createWord("ParallelGCImpl.allocChunkTL");
    private static final FastThreadLocalWord<AlignedHeapChunk.AlignedHeader> scannedChunkTL =
            FastThreadLocalFactory.createWord("ParallelGCImpl.scannedChunkTL");

    public static final VMMutex mutex = new VMMutex("ParallelGCImpl");
    private final VMCondition seqPhase = new VMCondition(mutex);
    private final VMCondition parPhase = new VMCondition(mutex);

    private ChunkBuffer buffer;
    private volatile boolean inParallelPhase;

    @Fold
    public static ParallelGCImpl singleton() {
        return (ParallelGCImpl) ImageSingletons.lookup(ParallelGC.class);
    }

    public static boolean isInParallelPhase() {
        return singleton().inParallelPhase;
    }

    public static void waitForIdle() {
        singleton().waitForIdleImpl();
    }

    public static AlignedHeapChunk.AlignedHeader getThreadLocalScannedChunk() {
        return scannedChunkTL.get();
    }

    public static AlignedHeapChunk.AlignedHeader getThreadLocalChunk() {
        return allocChunkTL.get();
    }

    public static void setThreadLocalChunk(AlignedHeapChunk.AlignedHeader chunk) {
        allocChunkTL.set(chunk);
    }

    public void push(Pointer ptr) {
        buffer.push(ptr);
    }

    @Override
    public void startWorkerThreadsImpl() {
        int hubOffset = ConfigurationValues.getObjectLayout().getHubOffset();
        VMError.guarantee(hubOffset == 0, "hub offset must be 0");

        // Allocate buffer large enough to store maximum possible number of heap chunks
        long maxHeapSize = GCImpl.getPolicy().getMaximumHeapSize().rawValue();
        long alignedChunkSize = HeapParameters.getAlignedHeapChunkSize().rawValue();
        long unalignedChunkSize = HeapParameters.getLargeArrayThreshold().rawValue();
        long maxChunks = maxHeapSize / Math.min(alignedChunkSize, unalignedChunkSize) + 1;
        buffer = new ChunkBuffer((int) maxChunks, ConfigurationValues.getObjectLayout().getReferenceSize());

        workerCount = getWorkerCount();
        allWorkersBusy = ~(-1 << workerCount) & (-1 << 1);
        // We reuse the gc thread for worker #0
        for (int i = 1; i < workerCount; i++) {
            startWorkerThread(i);
        }
    }

    private int getWorkerCount() {
        int setting = ParallelGCOptions.ParallelGCWorkers.getValue();
        int workers = setting > 0 ? setting : getDefaultWorkerCount();
        workers = Math.min(workers, MAX_WORKERS_COUNT);
        verboseGCLog().string("[Number of ParallelGC workers: ").unsigned(workers).string("]").newline();
        return workers;
    }

    private int getDefaultWorkerCount() {
        // Adapted from Hotspot, see WorkerPolicy::nof_parallel_worker_threads()
        int cpus = Jvm.JVM_ActiveProcessorCount();
        return cpus <= 8 ? cpus : 8 + (cpus - 8) * 5 / 8;
    }

    private void startWorkerThread(int n) {
        Thread t = new Thread(() -> {
                VMThreads.SafepointBehavior.markThreadAsCrashed();
                debugLog().string("WW start ").unsigned(n).newline();

                while (true) {
                    try {
                        do {
                            debugLog().string("WW block ").unsigned(n).newline();
                            mutex.lock();
                            parPhase.block();
                            mutex.unlock();
                        } while ((busyWorkers.get() & (1 << n)) == 0);

                        debugLog().string("WW run ").unsigned(n).newline();
                        drainBuffer();
                        int witness = busyWorkers.get();
                        int expected;
                        do {
                            expected = witness;
                            witness = busyWorkers.compareAndExchange(expected, expected & ~(1 << n));
                        } while (witness != expected);
                        if (busyWorkers.get() == 0) {
                            mutex.lock();
                            seqPhase.signal();
                            mutex.unlock();
                        }
                        debugLog().string("WW idle ").unsigned(n).newline();
                    } catch (Throwable ex) {
                        VMError.shouldNotReachHere(ex);
                    }
                }
        });
        t.setName("ParallelGCWorker-" + n);
        t.setDaemon(true);
        t.start();
    }

    private void waitForIdleImpl() {
        inParallelPhase = true;

        debugLog().string("PP start workers\n");
        busyWorkers.set(allWorkersBusy);
        parPhase.broadcast();     // let worker threads run

        drainBuffer();

        mutex.lock();
        while (busyWorkers.get() != 0) {
            debugLog().string("PP wait busy=").unsigned(busyWorkers.get()).newline();
            seqPhase.block();     // wait for them to become idle
        }
        mutex.unlock();

        inParallelPhase = false;
    }

    private void drainBuffer() {
        while (true) {
            Pointer ptr;
            while ((ptr = buffer.pop()).notEqual(WordFactory.nullPointer())) {
                debugLog().string("WW drain chunk=").zhex(ptr).newline();
                if (ptr.and(UNALIGNED_BIT).notEqual(0)) {
                    UnalignedHeapChunk.walkObjectsInline((UnalignedHeapChunk.UnalignedHeader) ptr.and(~UNALIGNED_BIT), getVisitor());
                } else {
                    AlignedHeapChunk.walkObjectsInline((AlignedHeapChunk.AlignedHeader) ptr, getVisitor());
                }
            }
            AlignedHeapChunk.AlignedHeader tlab = allocChunkTL.get();
            debugLog().string("WW drain tlab=").zhex(tlab).newline();
            if (tlab.equal(WordFactory.nullPointer())) {
                break;
            } else {
                scannedChunkTL.set(tlab);
                AlignedHeapChunk.walkObjectsInline(tlab, getVisitor());
                if (allocChunkTL.get().equal(tlab)) {
                    // this tlab is now black, retire it
                    allocChunkTL.set(WordFactory.nullPointer());
                }
            }
        }
        scannedChunkTL.set(WordFactory.nullPointer());
    }

    private GreyToBlackObjectVisitor getVisitor() {
        return GCImpl.getGCImpl().getGreyToBlackObjectVisitor();
    }

    private static Log verboseGCLog() {
        return SubstrateGCOptions.VerboseGC.getValue() ? Log.log() : Log.noopLog();
    }

    static Log debugLog() {
        return Log.noopLog();
    }
}

@AutomaticFeature
class ParallelGCFeature implements Feature {

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return ParallelGC.isSupported();
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(ParallelGC.class, new ParallelGCImpl());
    }
}

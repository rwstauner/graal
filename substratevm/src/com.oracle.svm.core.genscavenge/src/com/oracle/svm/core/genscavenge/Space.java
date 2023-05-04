/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.genscavenge;

import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.VERY_SLOW_PATH_PROBABILITY;
import static org.graalvm.compiler.nodes.extended.BranchProbabilityNode.probability;

import org.graalvm.compiler.word.ObjectAccess;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.MemoryWalker;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.genscavenge.GCImpl.ChunkReleaser;
import com.oracle.svm.core.genscavenge.parallel.ParallelGC;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.heap.ObjectHeader;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.identityhashcode.IdentityHashCodeSupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;

/**
 * A Space is a collection of HeapChunks.
 *
 * Each Space keeps two collections: one of {@link AlignedHeapChunk} and one of
 * {@link UnalignedHeapChunk}.
 */
public final class Space {
    private final String name;
    private final boolean isFromSpace;
    private final int age;
    private final ChunksAccounting accounting;

    /* Heads and tails of the HeapChunk lists. */
    private AlignedHeapChunk.AlignedHeader firstAlignedHeapChunk;
    private AlignedHeapChunk.AlignedHeader lastAlignedHeapChunk;
    private UnalignedHeapChunk.UnalignedHeader firstUnalignedHeapChunk;
    private UnalignedHeapChunk.UnalignedHeader lastUnalignedHeapChunk;

    /**
     * Space creation is HOSTED_ONLY because all Spaces must be constructed during native image
     * generation so they end up in the native image heap because they need to be accessed during
     * collections so they should not move.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    Space(String name, boolean isFromSpace, int age) {
        this(name, isFromSpace, age, null);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    Space(String name, boolean isFromSpace, int age, ChunksAccounting accounting) {
        assert name != null : "Space name should not be null.";
        this.name = name;
        this.isFromSpace = isFromSpace;
        this.age = age;
        this.accounting = new ChunksAccounting(accounting);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public String getName() {
        return name;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isEmpty() {
        return (getFirstAlignedHeapChunk().isNull() && getFirstUnalignedHeapChunk().isNull());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void tearDown() {
        HeapChunkProvider.freeAlignedChunkList(getFirstAlignedHeapChunk());
        HeapChunkProvider.freeUnalignedChunkList(getFirstUnalignedHeapChunk());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isEdenSpace() {
        return age == 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isYoungSpace() {
        return age <= HeapParameters.getMaxSurvivorSpaces();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isSurvivorSpace() {
        return age > 0 && age <= HeapParameters.getMaxSurvivorSpaces();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isOldSpace() {
        return age == (HeapParameters.getMaxSurvivorSpaces() + 1);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    int getAge() {
        return age;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    int getNextAgeForPromotion() {
        return age + 1;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    boolean isFromSpace() {
        return isFromSpace;
    }

    public boolean walkObjects(ObjectVisitor visitor) {
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            if (!AlignedHeapChunk.walkObjects(aChunk, visitor)) {
                return false;
            }
            aChunk = HeapChunk.getNext(aChunk);
        }
        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            if (!UnalignedHeapChunk.walkObjects(uChunk, visitor)) {
                return false;
            }
            uChunk = HeapChunk.getNext(uChunk);
        }
        return true;
    }

    /** Report some statistics about this Space. */
    public Log report(Log log, boolean traceHeapChunks) {
        log.string(getName()).string(":").indent(true);
        accounting.report(log);
        if (traceHeapChunks) {
            HeapChunkLogging.logChunks(log, getFirstAlignedHeapChunk());
            HeapChunkLogging.logChunks(log, getFirstUnalignedHeapChunk());
        }
        log.redent(false);
        return log;
    }

    /**
     * Allocate memory from an AlignedHeapChunk in this Space.
     */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateMemory(UnsignedWord objectSize) {
        if (ParallelGC.isEnabled() && GCImpl.getGCImpl().isCompleteCollection()) {
            return allocateMemoryParallel(objectSize);
        }
        return allocateMemorySerial(objectSize);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateMemorySerial(UnsignedWord objectSize) {
        /* Fast-path: try allocating in the last chunk. */
        AlignedHeapChunk.AlignedHeader oldChunk = getLastAlignedHeapChunk();
        if (oldChunk.isNonNull()) {
            Pointer result = AlignedHeapChunk.allocateMemory(oldChunk, objectSize);
            if (result.isNonNull()) {
                return result;
            }
        }
        /* Slow-path: try allocating a new chunk for the requested memory. */
        return allocateInNewChunk(objectSize);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateMemoryParallel(UnsignedWord objectSize) {
        /* Fast-path: try allocating in the thread local allocation chunk. */
        AlignedHeapChunk.AlignedHeader oldChunk = ParallelGC.singleton().getAllocationChunk();
        if (oldChunk.isNonNull()) {
            Pointer result = AlignedHeapChunk.allocateMemory(oldChunk, objectSize);
            if (result.isNonNull()) {
                return result;
            }
        }
        /* Slow-path: try allocating a new chunk for the requested memory. */
        return allocateInNewChunkParallel(objectSize);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateInNewChunkParallel(UnsignedWord objectSize) {
        AlignedHeapChunk.AlignedHeader newChunk;
        ParallelGC.singleton().getMutex().lockNoTransitionUnspecifiedOwner();
        try {
            ParallelGC.singleton().pushAllocChunk();
            newChunk = requestAlignedHeapChunk();
        } finally {
            ParallelGC.singleton().getMutex().unlockNoTransitionUnspecifiedOwner();
        }

        ParallelGC.singleton().setAllocationChunk(newChunk);
        if (newChunk.isNonNull()) {
            return AlignedHeapChunk.allocateMemory(newChunk, objectSize);
        }
        return WordFactory.nullPointer();
    }

    /** Retract the latest allocation. */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void retractAllocationParallel(UnsignedWord objectSize) {
        assert ParallelGC.isEnabled() && ParallelGC.singleton().isInParallelPhase();
        AlignedHeapChunk.AlignedHeader oldChunk = ParallelGC.singleton().getAllocationChunk();
        assert oldChunk.isNonNull();
        AlignedHeapChunk.retractAllocation(oldChunk, objectSize);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Pointer allocateInNewChunk(UnsignedWord objectSize) {
        AlignedHeapChunk.AlignedHeader newChunk = requestAlignedHeapChunk();
        if (newChunk.isNonNull()) {
            return AlignedHeapChunk.allocateMemory(newChunk, objectSize);
        }
        return WordFactory.nullPointer();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void releaseChunks(ChunkReleaser chunkReleaser) {
        chunkReleaser.add(firstAlignedHeapChunk);
        chunkReleaser.add(firstUnalignedHeapChunk);

        firstAlignedHeapChunk = WordFactory.nullPointer();
        lastAlignedHeapChunk = WordFactory.nullPointer();
        firstUnalignedHeapChunk = WordFactory.nullPointer();
        lastUnalignedHeapChunk = WordFactory.nullPointer();
        accounting.reset();
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    void appendAlignedHeapChunk(AlignedHeapChunk.AlignedHeader aChunk, Space originalSpace) {
        assert verifyMutualExclusionForAppendChunk() : "Trying to append an aligned heap chunk but no mutual exclusion.";
        assert HeapChunk.getSpace(aChunk) == originalSpace;
        assert this != originalSpace;

        if (originalSpace != null) {
            originalSpace.extractAlignedHeapChunk(aChunk);
        }

        HeapChunk.setSpace(aChunk, this);
        AlignedHeapChunk.AlignedHeader oldLast = getLastAlignedHeapChunk();
        HeapChunk.setPrevious(aChunk, oldLast);
        HeapChunk.setNext(aChunk, WordFactory.nullPointer());
        if (oldLast.isNonNull()) {
            HeapChunk.setNext(oldLast, aChunk);
        }
        setLastAlignedHeapChunk(aChunk);
        if (getFirstAlignedHeapChunk().isNull()) {
            setFirstAlignedHeapChunk(aChunk);
        }
        accounting.noteAlignedHeapChunk();
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    void appendUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader uChunk, Space originalSpace) {
        assert verifyMutualExclusionForAppendChunk() : "Trying to append an aligned heap chunk but no mutual exclusion.";
        assert HeapChunk.getSpace(uChunk) == originalSpace;
        assert this != originalSpace;

        if (originalSpace != null) {
            originalSpace.extractUnalignedHeapChunk(uChunk);
        }

        HeapChunk.setSpace(uChunk, this);
        UnalignedHeapChunk.UnalignedHeader oldLast = getLastUnalignedHeapChunk();
        HeapChunk.setPrevious(uChunk, oldLast);
        HeapChunk.setNext(uChunk, WordFactory.nullPointer());
        if (oldLast.isNonNull()) {
            HeapChunk.setNext(oldLast, uChunk);
        }
        setLastUnalignedHeapChunk(uChunk);
        if (getFirstUnalignedHeapChunk().isNull()) {
            setFirstUnalignedHeapChunk(uChunk);
        }
        accounting.noteUnalignedHeapChunk(uChunk);
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void extractAlignedHeapChunk(AlignedHeapChunk.AlignedHeader aChunk) {
        assert VMOperation.isGCInProgress();

        AlignedHeapChunk.AlignedHeader chunkNext = HeapChunk.getNext(aChunk);
        AlignedHeapChunk.AlignedHeader chunkPrev = HeapChunk.getPrevious(aChunk);
        if (chunkPrev.isNonNull()) {
            HeapChunk.setNext(chunkPrev, chunkNext);
        } else {
            setFirstAlignedHeapChunk(chunkNext);
        }
        if (chunkNext.isNonNull()) {
            HeapChunk.setPrevious(chunkNext, chunkPrev);
        } else {
            setLastAlignedHeapChunk(chunkPrev);
        }
        HeapChunk.setNext(aChunk, WordFactory.nullPointer());
        HeapChunk.setPrevious(aChunk, WordFactory.nullPointer());
        HeapChunk.setSpace(aChunk, null);
        accounting.unnoteAlignedHeapChunk();
    }

    @Uninterruptible(reason = "Must not interact with garbage collections.")
    private void extractUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader uChunk) {
        assert VMOperation.isGCInProgress();

        UnalignedHeapChunk.UnalignedHeader chunkNext = HeapChunk.getNext(uChunk);
        UnalignedHeapChunk.UnalignedHeader chunkPrev = HeapChunk.getPrevious(uChunk);
        if (chunkPrev.isNonNull()) {
            HeapChunk.setNext(chunkPrev, chunkNext);
        } else {
            setFirstUnalignedHeapChunk(chunkNext);
        }
        if (chunkNext.isNonNull()) {
            HeapChunk.setPrevious(chunkNext, chunkPrev);
        } else {
            setLastUnalignedHeapChunk(chunkPrev);
        }
        /* Reset the fields that the result chunk keeps for Space. */
        HeapChunk.setNext(uChunk, WordFactory.nullPointer());
        HeapChunk.setPrevious(uChunk, WordFactory.nullPointer());
        HeapChunk.setSpace(uChunk, null);
        accounting.unnoteUnalignedHeapChunk(uChunk);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean verifyMutualExclusionForAppendChunk() {
        return !SubstrateOptions.MultiThreaded.getValue() ||
                        VMThreads.ownsThreadMutex(true) ||
                        ParallelGC.isEnabled() && VMOperation.isGCInProgress() && ParallelGC.singleton().isInParallelPhase() && ParallelGC.singleton().getMutex().isOwner(true);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public AlignedHeapChunk.AlignedHeader getFirstAlignedHeapChunk() {
        return firstAlignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setFirstAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk) {
        firstAlignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    AlignedHeapChunk.AlignedHeader getLastAlignedHeapChunk() {
        return lastAlignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setLastAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk) {
        lastAlignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnalignedHeapChunk.UnalignedHeader getFirstUnalignedHeapChunk() {
        return firstUnalignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setFirstUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk) {
        this.firstUnalignedHeapChunk = chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnalignedHeapChunk.UnalignedHeader getLastUnalignedHeapChunk() {
        return lastUnalignedHeapChunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void setLastUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk) {
        lastUnalignedHeapChunk = chunk;
    }

    /** Promote an aligned Object to this Space. */
    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    Object promoteAlignedObject(Object original, Space originalSpace) {
        assert ObjectHeaderImpl.isAlignedObject(original);
        assert this != originalSpace && originalSpace.isFromSpace();

        if (ParallelGC.isEnabled() && ParallelGC.singleton().isInParallelPhase()) {
            return copyAlignedObjectParallel(original);
        }
        return copyAlignedObjectSerial(original);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Object copyAlignedObjectSerial(Object originalObj) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(originalObj);

        UnsignedWord originalSize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
            Word header = ObjectHeader.readHeaderFromObject(originalObj);
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(header))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromObjectInlineInGC(originalObj, true);
            }
        }

        Pointer copyMemory = allocateMemory(copySize);
        if (probability(VERY_SLOW_PATH_PROBABILITY, copyMemory.isNull())) {
            return null;
        }

        /*
         * This does a direct memory copy, without regard to whether the copied data contains object
         * references. That's okay, because all references in the copy are visited and overwritten
         * later on anyways (the card table is also updated at that point if necessary).
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(originalObj);
        UnmanagedMemoryUtil.copyLongsForward(originalMemory, copyMemory, originalSize);

        Object copy = copyMemory.toObject();
        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            // Must do first: ensures correct object size below and in other places
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(originalObj);
            int offset = LayoutEncoding.getOptionalIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }
        if (isOldSpace()) {
            // If the object was promoted to the old gen, we need to take care of the remembered
            // set bit and the first object table (even when promoting from old to old).
            AlignedHeapChunk.AlignedHeader copyChunk = AlignedHeapChunk.getEnclosingChunk(copy);
            RememberedSet.get().enableRememberedSetForObject(copyChunk, copy);
        }

        ObjectHeaderImpl.getObjectHeaderImpl().installForwardingPointer(originalObj, copy);
        return copy;
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private Object copyAlignedObjectParallel(Object original) {
        assert VMOperation.isGCInProgress();
        assert ObjectHeaderImpl.isAlignedObject(original);

        /*
         * Always read 8 bytes at the hub offset so that we can install the forwarding header with
         * cmpxchng.
         */
        Pointer originalMemory = Word.objectToUntrackedPointer(original);
        int hubOffset = ObjectHeader.getHubOffset();
        long eightHeaderBytes = originalMemory.readLong(hubOffset);
        Word originalHeader = ObjectHeaderImpl.hasShift() ? WordFactory.unsigned(eightHeaderBytes & 0xFFFFFFFFL) : WordFactory.unsigned(eightHeaderBytes);
        ObjectHeaderImpl ohi = ObjectHeaderImpl.getObjectHeaderImpl();
        if (ObjectHeaderImpl.isForwardedHeader(originalHeader)) {
            return ohi.getForwardedObject(originalMemory, originalHeader);
        }

        /*
         * We need the forwarding pointer to point somewhere, so we speculatively allocate memory
         * here. If another thread copies the object first, we retract the allocation later.
         */
        UnsignedWord originalSize = LayoutEncoding.getSizeFromHeader(original, originalHeader, false);
        UnsignedWord copySize = originalSize;
        boolean addIdentityHashField = false;
        if (!ConfigurationValues.getObjectLayout().hasFixedIdentityHashField()) {
            if (probability(SLOW_PATH_PROBABILITY, ObjectHeaderImpl.hasIdentityHashFromAddressInline(originalHeader))) {
                addIdentityHashField = true;
                copySize = LayoutEncoding.getSizeFromHeader(original, originalHeader, true);
            }
        }

        assert copySize.aboveThan(0);
        Pointer copyMemory = allocateMemoryParallel(copySize);
        if (probability(VERY_SLOW_PATH_PROBABILITY, copyMemory.isNull())) {
            return null;
        }

        /* Install forwarding pointer into the original header. */
        Object copy = copyMemory.toObject();
        Object forward = ohi.installForwardingPointerParallel(original, eightHeaderBytes, copy);
        if (forward != copy) {
            /* We lost the race. Retract speculatively allocated memory. */
            retractAllocationParallel(originalSize);
            return forward;
        }

        /* We have won the race. Install the object header and copy the rest of the object. */
        copyMemory.writeLong(hubOffset, eightHeaderBytes);
        if (hubOffset > 0) {
            UnmanagedMemoryUtil.copyLongsForward(originalMemory, copyMemory, WordFactory.unsigned(hubOffset));
        }
        int offset = hubOffset + Long.BYTES;
        UnmanagedMemoryUtil.copyLongsForward(originalMemory.add(offset), copyMemory.add(offset), originalSize.subtract(offset));

        if (probability(SLOW_PATH_PROBABILITY, addIdentityHashField)) {
            int value = IdentityHashCodeSupport.computeHashCodeFromAddress(original);
            offset = LayoutEncoding.getOptionalIdentityHashOffset(copy);
            ObjectAccess.writeInt(copy, offset, value, IdentityHashCodeSupport.IDENTITY_HASHCODE_LOCATION);
            ObjectHeaderImpl.getObjectHeaderImpl().setIdentityHashInField(copy);
        }

        if (isOldSpace()) {
            // If the object was promoted to the old gen, we need to take care of the remembered
            // set bit and the first object table (even when promoting from old to old).
            AlignedHeapChunk.AlignedHeader copyChunk = AlignedHeapChunk.getEnclosingChunk(copy);
            RememberedSet.get().enableRememberedSetForObject(copyChunk, copy);
        }
        return copy;
    }

    /** Promote an AlignedHeapChunk by moving it to this space. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void promoteAlignedHeapChunk(AlignedHeapChunk.AlignedHeader chunk) {
        if (ParallelGC.isEnabled() && ParallelGC.singleton().isInParallelPhase()) {
            promoteAlignedHeapChunkParallel(chunk);
        } else {
            promoteAlignedHeapChunkSerial(chunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteAlignedHeapChunkSerial(AlignedHeapChunk.AlignedHeader chunk) {
        Space originalSpace = HeapChunk.getSpace(chunk);
        promoteAlignedHeapChunk0(chunk, originalSpace);

        if (ParallelGC.isEnabled() && GCImpl.getGCImpl().isCompleteCollection()) {
            ParallelGC.singleton().push(chunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteAlignedHeapChunkParallel(AlignedHeapChunk.AlignedHeader chunk) {
        ParallelGC.singleton().getMutex().lockNoTransitionUnspecifiedOwner();
        try {
            Space originalSpace = HeapChunk.getSpace(chunk);
            if (!originalSpace.isFromSpace()) {
                /* The chunk was already promoted in the meanwhile. */
                return;
            }
            promoteAlignedHeapChunk0(chunk, originalSpace);
            ParallelGC.singleton().push(chunk);
        } finally {
            ParallelGC.singleton().getMutex().unlockNoTransitionUnspecifiedOwner();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteAlignedHeapChunk0(AlignedHeapChunk.AlignedHeader chunk, Space originalSpace) {
        assert originalSpace.isFromSpace();

        appendAlignedHeapChunk(chunk, originalSpace);
        if (this.isOldSpace()) {
            if (originalSpace.isYoungSpace()) {
                RememberedSet.get().enableRememberedSetForChunk(chunk);
            } else {
                assert originalSpace.isOldSpace();
                RememberedSet.get().clearRememberedSet(chunk);
            }
        }
    }

    /** Promote an UnalignedHeapChunk by moving it to this Space. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void promoteUnalignedHeapChunk(UnalignedHeapChunk.UnalignedHeader chunk) {
        if (ParallelGC.isEnabled() && ParallelGC.singleton().isInParallelPhase()) {
            promoteUnalignedHeapChunkParallel(chunk);
        } else {
            promoteUnalignedHeapChunkSerial(chunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteUnalignedHeapChunkSerial(UnalignedHeapChunk.UnalignedHeader chunk) {
        Space originalSpace = HeapChunk.getSpace(chunk);
        promoteUnalignedHeapChunk0(chunk, originalSpace);

        if (ParallelGC.isEnabled() && GCImpl.getGCImpl().isCompleteCollection()) {
            ParallelGC.singleton().push(chunk);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteUnalignedHeapChunkParallel(UnalignedHeapChunk.UnalignedHeader chunk) {
        ParallelGC.singleton().getMutex().lockNoTransitionUnspecifiedOwner();
        try {
            Space originalSpace = HeapChunk.getSpace(chunk);
            if (!originalSpace.isFromSpace()) {
                /* The chunk was already promoted in the meanwhile. */
                return;
            }
            promoteUnalignedHeapChunk0(chunk, originalSpace);
            ParallelGC.singleton().push(chunk);
        } finally {
            ParallelGC.singleton().getMutex().unlockNoTransitionUnspecifiedOwner();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteUnalignedHeapChunk0(UnalignedHeapChunk.UnalignedHeader chunk, Space originalSpace) {
        assert originalSpace.isFromSpace();

        appendUnalignedHeapChunk(chunk, originalSpace);
        if (this.isOldSpace()) {
            if (originalSpace.isYoungSpace()) {
                RememberedSet.get().enableRememberedSetForChunk(chunk);
            } else {
                assert originalSpace.isOldSpace();
                RememberedSet.get().clearRememberedSet(chunk);
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private AlignedHeapChunk.AlignedHeader requestAlignedHeapChunk() {
        AlignedHeapChunk.AlignedHeader chunk;
        if (isYoungSpace()) {
            assert isSurvivorSpace();
            chunk = HeapImpl.getHeapImpl().getYoungGeneration().requestAlignedSurvivorChunk();
        } else {
            chunk = HeapImpl.getHeapImpl().getOldGeneration().requestAlignedChunk();
        }
        if (chunk.isNonNull()) {
            appendAlignedHeapChunk(chunk, null);
        }
        return chunk;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    void absorb(Space src) {
        /*
         * Absorb the chunks of a source into this Space. I cannot just copy the lists, because each
         * HeapChunk has a reference to the Space it is in, so I have to touch them all.
         */
        AlignedHeapChunk.AlignedHeader aChunk = src.getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            AlignedHeapChunk.AlignedHeader next = HeapChunk.getNext(aChunk);
            appendAlignedHeapChunk(aChunk, src);
            aChunk = next;
        }
        UnalignedHeapChunk.UnalignedHeader uChunk = src.getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            UnalignedHeapChunk.UnalignedHeader next = HeapChunk.getNext(uChunk);
            appendUnalignedHeapChunk(uChunk, src);
            uChunk = next;
        }
    }

    boolean walkHeapChunks(MemoryWalker.Visitor visitor) {
        boolean continueVisiting = true;
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (continueVisiting && aChunk.isNonNull()) {
            continueVisiting = visitor.visitHeapChunk(aChunk, AlignedHeapChunk.getMemoryWalkerAccess());
            aChunk = HeapChunk.getNext(aChunk);
        }
        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (continueVisiting && uChunk.isNonNull()) {
            continueVisiting = visitor.visitHeapChunk(uChunk, UnalignedHeapChunk.getMemoryWalkerAccess());
            uChunk = HeapChunk.getNext(uChunk);
        }
        return continueVisiting;
    }

    /**
     * This value is only updated during a GC. Be careful when calling this method during a GC as it
     * might wrongly include chunks that will be freed at the end of the GC.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnsignedWord getChunkBytes() {
        assert !isEdenSpace() || VMOperation.isGCInProgress() : "eden data is only accurate during a GC";
        return getAlignedChunkBytes().add(accounting.getUnalignedChunkBytes());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    UnsignedWord getAlignedChunkBytes() {
        return accounting.getAlignedChunkBytes();
    }

    UnsignedWord computeObjectBytes() {
        assert !isEdenSpace() || VMOperation.isGCInProgress() : "eden data is only accurate during a GC";
        return computeAlignedObjectBytes().add(computeUnalignedObjectBytes());
    }

    private UnsignedWord computeAlignedObjectBytes() {
        UnsignedWord result = WordFactory.zero();
        AlignedHeapChunk.AlignedHeader aChunk = getFirstAlignedHeapChunk();
        while (aChunk.isNonNull()) {
            UnsignedWord allocatedBytes = HeapChunk.getTopOffset(aChunk).subtract(AlignedHeapChunk.getObjectsStartOffset());
            result = result.add(allocatedBytes);
            aChunk = HeapChunk.getNext(aChunk);
        }
        return result;
    }

    private UnsignedWord computeUnalignedObjectBytes() {
        UnsignedWord result = WordFactory.zero();
        UnalignedHeapChunk.UnalignedHeader uChunk = getFirstUnalignedHeapChunk();
        while (uChunk.isNonNull()) {
            UnsignedWord allocatedBytes = HeapChunk.getTopOffset(uChunk).subtract(UnalignedHeapChunk.getObjectStartOffset());
            result = result.add(allocatedBytes);
            uChunk = HeapChunk.getNext(uChunk);
        }
        return result;
    }
}

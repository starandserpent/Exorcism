package com.ritualsoftheold.exorcism.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class ConcurrentLinkedArray<E> implements Iterable<E> {
        
    private static final VarHandle arrayVar = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle headIndexVar;
    
    static {
        try {
            headIndexVar = MethodHandles.lookup().findVarHandle(ConcurrentLinkedArray.class, "headIndex", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
    
    private static final Object skipMarker = new Object();
    
    /**
     * Offset to reference of next array from end of array.
     */
    private static final int nextRefOffset = 1;
    
    /**
     * Offset to compaction marker (Boolean or null).
     */
    private static final int compactOffset = 2;
    
    /**
     * Offset from end of array to one after data.
     */
    private static final int dataOffset = 2;
    
    private final int compactTreshold;    
    /**
     * The first array. Iterations are started from there.
     */
    private final Object[] firstArray;
    
    /**
     * The last array. New entries are added to this, and when it runs out of
     * space, it is replaced with next array.
     */
    private volatile Object[] headArray;
    
    /**
     * Index to first free slot in current head array. This is also used to
     * identify when the head array runs out of space.
     */
    @SuppressWarnings("unused") // VarHandle
    private int headIndex;
    
    public ConcurrentLinkedArray(int arraySize, int recreateTreshold) {
        this.firstArray = allocArray(arraySize);
        this.headArray = firstArray;
        this.compactTreshold = recreateTreshold;
    }
    
    private Object[] allocArray(int size) {
        Object[] array = new Object[size];
        arrayVar.setVolatile(array, size - compactOffset, new CompactInfo());
        return array;
    }

    public void add(E entry) {
        while (true) {
            int oldIndex = (int) headIndexVar.getVolatile(this);
            Object[] ourArray = headArray;
            int index = (int) headIndexVar.getAndAdd(this, 1);
            if (index < oldIndex) { // We might have got the old array, but index from new one
                continue; // That is not ok, try again
            }
            
            // Do we need a new array?
            if (index >= ourArray.length - dataOffset) {
                // Possible entry points:
                // 1) We exhausted array
                // 2) Someone else exhausted it, but didn't yet create new array
                // 3) Someone else exhausted it, created new array but didn't update index yet
                
                // Check for case 3
                if (arrayVar.getVolatile(ourArray, 0) == null && ourArray != firstArray) {
                    // Oops... Need new array, but the one we have contains nothing!
                    boolean ok = headIndexVar.compareAndSet(this, 0, 1); // Try to update it
                    // If we fail, it doesn't matter; someone else succeeded
                    // Might be the thread that created new array or someone else that bumped to this CAS quicker
                }
                
                Object[] newArray = allocArray(firstArray.length);
                if (!arrayVar.compareAndSet(ourArray, ourArray.length - nextRefOffset, null, newArray)) {
                    continue; // Failed CAS here means almost certainly a race condition
                }
                // CAS ok, so we update the index
                headArray = newArray;
                ourArray = newArray; // And write goes there, too
                headIndexVar.setVolatile(this, 1); // First one is reserved for us
                index = 0;
            }
            
            // At this point, we always have index and array
            arrayVar.setVolatile(ourArray, index, entry);
            break;
        }
    }
    
    private class ArrayIterator<T> implements Iterator<T> {
        
        /**
         * The array we're currently iterating on.
         */
        private Object[] array;
        
        /**
         * Index in the array we're iterating on.
         */
        private int index;
        
        /**
         * Next entry in the collection, or null if not available  or
         * not yet cached.
         */
        private Object next;
        
        /**
         * Current entry. The iterator does not offer any way to get it,
         * but it is needed to support removing it in case compaction
         * moves it to previous array.
         */
        private Object current;
        
        /**
         * How many skip markers we have encountered in this array.
         */
        private int skipMarkers;
        
        /**
         * How much can be read from this array safely. After this many values
         * have been read, 
         */
        private int safeReadCount;
        
        /**
         * Reference to previous array, to be used when compaction is needed.
         * If current array is the first one, previous array is of course null.
         */
        private Object[] previousArray;
        
        protected ArrayIterator() {
            array = firstArray;
        }

        @Override
        public boolean hasNext() {
            Object next = getNext();
            if (next != null) {
                this.next = next; // Cache for next() call
                return true;
            }
            return false;
        }

        @Override
        public T next() {
            if (next == null) {
                hasNext(); // Not calling hasNext() is bad idea, but allowed
            }
            if (next == null) { // Now, this is an error: no more next
                throw new NoSuchElementException("no more elements");
            }
            
            @SuppressWarnings("unchecked")
            T next = (T) this.next;
            this.next = null;
            current = next;
            index++;
            
            return next;
        }
        
        @Override
        public void remove() {
            int prevIndex = index - 1;
            if (prevIndex == -1) { // Must access previous array
                prevIndex = previousArray.length - dataOffset - prevIndex;
                lockCompact(previousArray); // Ensure that compaction doesn't cancel remove
                
                // Try to just CAS it away
                if (!arrayVar.compareAndSet(previousArray, prevIndex, current, skipMarker)) {
                    // Somehow, the value has moved
                    // TODO handle this case
                    throw new AssertionError("blame bensku");
                }
                
                unlockCompact(previousArray); // Free it again
            } else { // Just remove the previous element
                arrayVar.setOpaque(array, prevIndex, skipMarker);
                skipMarkers++;
            }
        }
        
        private Object getNext() {
            // Skip the skip markers until we find something
            while (true) {
                // Check if we need the next array
                if (index == array.length - dataOffset) {
                    Object[] newArray = (Object[]) arrayVar.getOpaque(array, array.length - nextRefOffset);
                    if (newArray == null) {
                        unlockCompact(array);
                        return null; // Definitely no more elements
                    }
                    
                    // Unlock the old array and lock the new one
                    unlockCompact(array);
                    safeReadCount = lockCompact(newArray);
                    index = 0; // To beginning of new array
                    
                    // Put the new array in place
                    previousArray = array;
                    array = newArray;
                }
                
                // We can't read more entries without checking compaction
                while (index == safeReadCount) {
                    safeReadCount = refreshCompactLock(array);
                    Thread.onSpinWait(); // TODO find nonblocking solution
                }
                
                // Prepare to compact that array ourself, if it seems necessary
                if (skipMarkers > compactTreshold) {
                    index -= compactArray(previousArray, array);
                    if (index < 0) { // Need to visit previous array
                        index = previousArray.length - dataOffset + index; // Find index there
                        unlockCompact(array); // Might be that user never reads this again!
                        array = previousArray;
                        lockCompact(array);
                        continue; // Jump to code that acquires lock for our array
                    }
                }
                
                Object entry = arrayVar.getOpaque(array, index);
                if (entry != skipMarker) {
                    return entry; // Might be null, if that is end of list
                }
                skipMarkers++;
                index++;
            }
        }
        
    }
    
    protected int compactArray(Object[] previous, Object[] array) {
        CompactInfo info = (CompactInfo) arrayVar.getOpaque(array, array.length - compactOffset);
        if (!CompactInfo.statusVar.compareAndSet(info, CompactInfo.unused, CompactInfo.compactCurrent)) {
            return 0; // Someone else is compacting, we don't need to (and cannot) do it
        }
        
        int freeSlot = Integer.MAX_VALUE;
        if (previous != null) {
            CompactInfo prevInfo = (CompactInfo) arrayVar.getOpaque(previous, previous.length - compactOffset);
            
            // Look for free space in the previous array
            if ((int) CompactInfo.iteratorsVar.getOpaque(prevInfo) < 1) {
                for (int i = previous.length - dataOffset;;i--) {
                    Object entry = arrayVar.getOpaque(previous, i);
                    if (entry != skipMarker) {
                        break;
                    }
                    freeSlot = i;
                }
                
                // Check that no one else is trying to compact previous array
                if (!CompactInfo.statusVar.compareAndSet(prevInfo, CompactInfo.unused, freeSlot)) {
                    freeSlot = previous.length - dataOffset; // Previous array unavailable
                } else if ((int) CompactInfo.iteratorsVar.getOpaque(prevInfo) > 0) {
                    // Some iterators slipped there, whoops
                    freeSlot = previous.length - dataOffset; // Previous array unavailable
                    CompactInfo.statusVar.setVolatile(prevInfo, CompactInfo.unused); // Revert back to unused
                }
            }
        }
        
        // Figure out if there are iterators which disallow compaction here
        // We are likely to have one iterator ourself, but more than that is not ok
        while ((int) CompactInfo.iteratorsVar.getOpaque(info) > 1) {
            Thread.onSpinWait();
        }
        
        // Compact the array
        VarHandle.fullFence(); // Make sure all writes from everywhere have completed
        int moveBack = 0;
        outer: for (int i = 0; i < array.length - dataOffset; i++) {
            Object entry = arrayVar.getOpaque(array, i);
            if (entry == skipMarker) { // Skip markers are written over
                moveBack++;
                continue;
            }
            
            // If previous array has space, use it
            while (previous != null && freeSlot < previous.length - dataOffset) {
                boolean success = arrayVar.compareAndSet(previous, freeSlot, skipMarker, entry);
                freeSlot++;
                if (success) {
                    moveBack++;
                    continue outer;
                }
            }
            
            // Or maybe we just move it back in this array?
            if (moveBack != 0) {
                arrayVar.setOpaque(array, i - moveBack, entry);
            }
        }
        
        // Rest of array to skip markers
        for (int i = moveBack; i < array.length - dataOffset; i++) {
            arrayVar.setOpaque(array, i, skipMarker);
        }
        
        return moveBack;
    }
    
    private static class CompactInfo {
        
        protected static final VarHandle iteratorsVar;
        protected static final VarHandle statusVar;
        
        protected static final int unused = 0;
        protected static final int compactCurrent = -1;
        //protected static final int compactNext = 1; // or higher
        
        static {
            try {
                iteratorsVar = MethodHandles.lookup().findVarHandle(CompactInfo.class, "iterators", int.class);
                statusVar = MethodHandles.lookup().findVarHandle(CompactInfo.class, "status", int.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new Error(e);
            }
        }
        
        /**
         * How many iterators are at this array at the moment.
         */
        @SuppressWarnings("unused") // VarHandle
        private int iterators;
        
        @SuppressWarnings("unused") // VarHandle
        private int status;
    }
    
    protected int lockCompact(Object[] array) {
        CompactInfo info = (CompactInfo) arrayVar.getOpaque(array, array.length - compactOffset);
        CompactInfo.iteratorsVar.getAndAdd(info, 1); // We want to use that array
        
        while (true) {
            int status = (int) CompactInfo.statusVar.getOpaque(info);
            if (status == CompactInfo.unused) {
                return array.length - dataOffset; // Whole array freely usable
            } else if (status == CompactInfo.compactCurrent) {
                Thread.onSpinWait(); // Compaction is in progress
                // TODO if we wait too long, try alternative (?) way of iteration
            } else { // CompactInfo.compactNext
                // We can use our array until we'd hit skipMarkers at end
                return status;
            }
        }
    }
    
    protected void unlockCompact(Object[] array) {
        CompactInfo info = (CompactInfo) arrayVar.getOpaque(array, array.length - compactOffset);
        CompactInfo.iteratorsVar.getAndAdd(info, -1); // Reduce user count by 1
    }
    
    protected int refreshCompactLock(Object[] array) {
        CompactInfo info = (CompactInfo) arrayVar.getOpaque(array, array.length - compactOffset);
        
        int status = (int) CompactInfo.statusVar.getOpaque(info);
        if (status == CompactInfo.unused) {
            return array.length - dataOffset; // Whole array freely usable
        } else { // CompactInfo.compactNext
            // We can use our array until we'd hit skipMarkers at end
            return status;
        }
        // compactCurrent cannot happen, because we successfully acquired lock before
    }

    @Override
    public Iterator<E> iterator() {
        VarHandle.fullFence(); // Ensure no stores get reordered after this (StoreLoad barrier)
        return new ArrayIterator<>();
    }
    
}

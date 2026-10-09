package com.hongguotv.nativeapp.rayneo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class RowMemoryTest {
    static final class Rows implements RowMemory.Rows {
        Object[] items = {"A", "B", "C"};
        long[] ids = {10, 20, 30};
        boolean[] enabled = {true, true, true};
        boolean stable;
        public int count() { return items.length; }
        public boolean enabled(int i) { return enabled[i]; }
        public Object item(int i) { return items[i]; }
        public boolean hasStableIds() { return stable; }
        public long id(int i) { return ids[i]; }
    }
    @Test public void missingSelectionDoesNotEraseBrowsedRow() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows(); Object owner = new Object();
        memory.remember(owner, rows, 2); memory.remember(owner, rows, -1);
        assertEquals(2, memory.restore(owner, rows));
    }
    @Test public void stableIdRelocatesDespiteLabelChange() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows(); rows.stable = true;
        memory.remember(rows, rows, 2);
        rows.ids = new long[]{30, 10, 20}; rows.items = new Object[]{"C renamed", "A", "B"};
        assertEquals(0, memory.restore(rows, rows));
    }
    @Test public void equalUniqueValueRelocatesWithoutStableIds() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        memory.remember(rows, rows, 2);
        rows.items = new Object[]{new String("C"), "A", "B"};
        assertEquals(0, memory.restore(rows, rows));
    }
    @Test public void replacementOwnerAndDeletedItemAreRejected() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        memory.remember(rows, rows, 2);
        assertEquals(-1, memory.restore(new Object(), rows));
        rows.items[2] = "D";
        assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void disabledItemAndDisabledRememberAreRejected() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        memory.remember(rows, rows, 2); rows.enabled[1] = false;
        memory.remember(rows, rows, 1);
        assertEquals(2, memory.restore(rows, rows));
        rows.enabled[2] = false;
        assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void duplicateValuesRejectedEvenIfOneDisabled() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        memory.remember(rows, rows, 2); rows.items[0] = "C"; rows.enabled[0] = false;
        assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void duplicateStableIdsAreRejected() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows(); rows.stable = true;
        memory.remember(rows, rows, 2); rows.ids[0] = 30;
        assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void deletedStableIdDoesNotFallBackToLabelOrIndex() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows(); rows.stable = true;
        memory.remember(rows, rows, 2); rows.ids[2] = 99;
        assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void clearAndInvalidIndexLeaveNoAnchor() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        memory.remember(rows, rows, 99); assertEquals(-1, memory.restore(rows, rows));
        memory.remember(rows, rows, 2); memory.clear(); assertEquals(-1, memory.restore(rows, rows));
    }
    @Test public void checkedStateCannotOverrideBrowsedSelection() {
        RowMemory memory = new RowMemory(); Rows rows = new Rows();
        int checkedIndex = 0; // Deliberately outside this helper's API.
        memory.remember(rows, rows, 2); memory.remember(rows, rows, -1);
        assertEquals(0, checkedIndex); assertEquals(2, memory.restore(rows, rows));
    }
}

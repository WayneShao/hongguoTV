package com.hongguotv.nativeapp.rayneo;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** One list's browsed row identity, independent of its checked/activated state. */
public final class RowMemory {
    public interface Rows {
        int count();
        boolean enabled(int index);
        Object item(int index);
        boolean hasStableIds();
        long id(int index);
    }
    private WeakReference<Object> owner = new WeakReference<>(null);
    private boolean stable;
    private long itemId;
    private Object value;

    /** A transient missing selection must not erase the last valid browsed row. */
    public void remember(Object adapterOwner, Rows rows, int index) {
        if (adapterOwner == null || rows == null || index < 0 || index >= rows.count() || !rows.enabled(index)) return;
        owner = new WeakReference<>(adapterOwner);
        stable = rows.hasStableIds();
        itemId = stable ? rows.id(index) : 0;
        value = stable ? null : rows.item(index);
    }

    /** Resolve identity afresh; never treat an old index as identity after reordering. */
    public int restore(Object adapterOwner, Rows rows) {
        if (adapterOwner == null || owner.get() != adapterOwner || rows == null || stable != rows.hasStableIds()) return -1;
        int found = -1;
        for (int i = 0, count = rows.count(); i < count; i++) {
            boolean matches = stable ? rows.id(i) == itemId : Objects.equals(value, rows.item(i));
            if (!matches) continue;
            // Disabled duplicates still make value identity ambiguous.
            if (found >= 0) return -1;
            found = i;
        }
        return found >= 0 && rows.enabled(found) ? found : -1;
    }

    public void clear() { owner.clear(); value = null; itemId = 0; stable = false; }
}

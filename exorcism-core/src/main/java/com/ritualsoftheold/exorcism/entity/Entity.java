package com.ritualsoftheold.exorcism.entity;

import com.ritualsoftheold.exorcism.entity.component.Component;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;


/**
 * An entity is something that can hold multiple {@link Component}s.
 *
 */
public final class Entity {
    
    /**
     * Private field 'currentSlot' in Component.
     */
    private static final VarHandle curSlotVar;
    
    static {
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(Component.class, MethodHandles.lookup());
            curSlotVar = lookup.findVarHandle(Component.class, "currentSlot", int.class);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
    
    /**
     * A specialized open hash table holding components of this entity.
     */
    private Component[] components;
    
    /**
     * How many free slots {@link #components} has. When a component is added
     * but no more slots are available, the array will be enlarged.
     */
    private int freeSlots;
    
    /**
     * X, Y, and Z coordinates of this entity. TODO unused at the moment
     */
    private float x, y, z;
    
    /**
     * Bit shifting is a fast way to divide, as long as the divisor is
     * power of two. The divisor for component slot lookup is
     * 2^shiftDiv.
     */
    private int shiftDiv;

    /**
     * Entity marked with this will be removed when it is reached by entity
     * iterator. It will not be processed by systems after this has been set.
     */
    private boolean removalMark;
    
    protected Entity(int ourPow, int maxPow) {
        if (maxPow < ourPow) { // Less components
            ourPow = maxPow;
        }
        this.components = new Component[1 << ourPow]; // Math.pow(2, ourPow)
        this.shiftDiv = maxPow - ourPow;
    }
    
    protected void addComponent(Component component, int slot) {
        if (freeSlots == 0) { // Need to enlarge components array
            doubleArray();
        }
        
        int realSlot = slot >>> shiftDiv;
        Component old = components[realSlot];
        if (old == null) { // Just place component at given slot
            components[realSlot] = component;
            curSlotVar.set(component, realSlot);
        } else { // Find first free slot, place there
            for (int i = 0; i < components.length; i++) {
                if (components[i] == null) {
                    components[i] = component;
                    curSlotVar.set(component, i);
                }
            }
        }
        freeSlots--;
    }
    
    protected void removeComponent(Component component) {
        // This is fast, because we can just get the current slot
        int slot = (int) curSlotVar.get(component);
        components[slot] = null;
        freeSlots++; // One less component now
    }
    
    protected void removeComponent(Class<? extends Component> type, int slot) {
        // Slower than if we already had the exact component instance
        int realSlot = slot >>> shiftDiv;
        Component component = components[realSlot];
        if (component != null && component.getClass() == type) { // Found where it should be
            components[realSlot] = null; // Just remove it
        } else { // Not found, iterate over all components
            for (int i = 0; i < components.length; i++) {
                Component c = components[i];
                if (c.getClass() == type) {
                    components[i] = null;
                    // As opposed to getComponent, no need to swap slots
                }
            }
        }
        freeSlots++; // One less component now
    }
    
    private void doubleArray() {
        Component[] newArray = new Component[components.length * 2]; // Double capacity
        System.arraycopy(components, 0, newArray, 0, components.length);
        // Spreading out components to optimal slots can be done at when getting
        components = newArray;
        if (shiftDiv > 0) { // Divide slots less in future
            shiftDiv -= 1; // divisor / 2
        }
    }
    
    protected Component getComponent(Class<?> type, int slot) {
        int realSlot = slot >>> shiftDiv;
        Component component = components[realSlot];
        if (component != null && component.getClass() == type) { // Found where it should be
            return component;
        } else { // Not found, iterate over all components
            for (int i = 0; i < components.length; i++) {
                Component c = components[i];
                if (c.getClass() == type) {
                    // Swap this to where it should be
                    components[realSlot] = c;
                    curSlotVar.set(c, realSlot);
                    components[i] = component; // Overwrite, possibly with null
                    if (component != null) {
                        curSlotVar.set(component, i);
                    }
                    
                    return c;
                }
            }
        }
        
        return null;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float getZ() {
        return z;
    }

    public void setZ(float z) {
        this.z = z;
    }

    /**
     * Checks if this entity is marked for removal.
     * @return Whether this entity is marked for removal.
     */
    public boolean isMarkedForRemoval() {
        return removalMark;
    }

    /**
     * Marks this entity for removal.
     */
    public void remove() {
        this.removalMark = true;
    }
}

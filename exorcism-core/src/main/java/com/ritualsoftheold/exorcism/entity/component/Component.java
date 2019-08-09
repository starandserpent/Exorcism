package com.ritualsoftheold.exorcism.entity.component;

/**
 * Components are added to entities to store data in said entities. This allows
 * adding new features to existing entities without being hindered by lack
 * of multiple inheritance.
 * 
 * <p>Technically, a component is an instance of a class that:
 * <ul>
 * <li>Is not anonymous or abstract
 * <li>Has all data accessible in public fields
 * </ul>
 *
 */
public abstract class Component {
    
    protected int originSlot;
    
    protected int currentSlot;
    
    protected Component(int slot) {
        this.originSlot = slot;
    }
}

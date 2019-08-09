package com.ritualsoftheold.exorcism.entity.component;

/**
 * Stores a position and a world.
 *
 */
public class PositionComponent extends com.ritualsoftheold.common.entity.component.Component {

    private static int slot;
    
    public PositionComponent() {
        super(slot);
    }
    
    /**
     * Actual coordinates.
     */
    public float x, y, z;
}

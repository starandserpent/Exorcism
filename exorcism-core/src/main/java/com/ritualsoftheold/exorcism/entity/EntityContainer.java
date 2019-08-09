package com.ritualsoftheold.exorcism.entity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class EntityContainer {

    private static final VarHandle usersVar;
    
    static {
        try {
            usersVar = MethodHandles.lookup().findVarHandle(EntityContainer.class, "users", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }
    
    @SuppressWarnings("unused") // VarHandle
    private int users;
    
    private List<Entity> entities;
    
    public final float x, y, z;
    
    public final float scale;
    
    public EntityContainer(float scale, float x, float y, float z) {
        this.entities = new ArrayList<>();
        this.scale = scale;
        this.x = x;
        this.y = y;
        this.z = z;
    }
    
    public void used() {
        usersVar.getAndAdd(this, 1);
    }

    public void unused() {
        usersVar.getAndAdd(this, -1);
    }

    public boolean isUsed() {
        return ((int) usersVar.get(this)) > 0;
    }
    
    public Collection<Entity> getEntities() {
        return entities;
    }
}

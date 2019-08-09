package com.ritualsoftheold.exorcism.entity.system;

import java.util.Collection;
import java.util.Iterator;

import com.ritualsoftheold.common.entity.Entity;

public class SimpleIterator implements EntityIterator {

    private Collection<System> systems;
    
    public SimpleIterator(Collection<System> systems) {
        this.systems = systems;
    }
    
    @Override
    public void process(float tpf, Iterator<Entity> entities) {
        while (entities.hasNext()) {
            Entity entity = entities.next();
            if (entity.isMarkedForRemoval()) {
                entities.remove();
                continue;
            }
            
            for (System system : systems) {
                system.process(tpf, entity);
            }
        }
    }

}

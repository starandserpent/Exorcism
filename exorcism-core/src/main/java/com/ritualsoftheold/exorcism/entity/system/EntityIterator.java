package com.ritualsoftheold.exorcism.entity.system;

import java.util.Iterator;

import com.ritualsoftheold.common.entity.Entity;

public interface EntityIterator {
    
    void process(float tpf, Iterator<Entity> entities);
}

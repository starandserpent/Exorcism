package com.ritualsoftheold.exorcism;

/**
 * Errors to use for crashing the game.
 *
 */
public enum CriticalError {
    
    /**
     * Material registry unavailable.
     */
    MATERIAL_REGISTRY(1);
    
    private int code;
    
    CriticalError(int code) {
        this.code = code;
    }
    
    public void trigger() {
        System.exit(code);
    }
}

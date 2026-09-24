package com.elementalphase.data.model;

public record ElementApplicationPolicy(boolean fromAttack, boolean fromReaction) {
    public static final ElementApplicationPolicy DEFAULT = new ElementApplicationPolicy(true, true);
}

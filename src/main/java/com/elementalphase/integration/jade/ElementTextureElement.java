package com.elementalphase.integration.jade;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.ui.Element;

public final class ElementTextureElement extends Element {
    private static final float SIZE = 9.0F;
    private final ResourceLocation texture;

    public ElementTextureElement(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public Vec2 getSize() {
        return new Vec2(SIZE, SIZE);
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float maxX, float maxY) {
        graphics.blit(texture, (int) x, (int) y, (int) SIZE, (int) SIZE,
                0.0F, 0.0F, 16, 16, 16, 16);
    }
}

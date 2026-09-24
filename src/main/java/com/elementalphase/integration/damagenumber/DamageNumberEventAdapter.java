package com.elementalphase.integration.damagenumber;

import net.minecraft.network.chat.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

public final class DamageNumberEventAdapter {
    private static final String EVENT_CLASS = "cc.xypp.damage_number.api.DamageEmitEvent";
    private final Class<?> eventType;
    private final Method getSource;
    private final Method getPlayer;
    private final Method getFormat;
    private final Method setColor;
    private final Method append;

    private DamageNumberEventAdapter(Class<?> eventType, Method getSource, Method getPlayer,
                                     Method getFormat, Method setColor, Method append) {
        this.eventType = eventType;
        this.getSource = getSource;
        this.getPlayer = getPlayer;
        this.getFormat = getFormat;
        this.setColor = setColor;
        this.append = append;
    }

    public static Optional<DamageNumberEventAdapter> create(ClassLoader classLoader) {
        try {
            Class<?> eventType = Class.forName(EVENT_CLASS, false, classLoader);
            Method getSource = eventType.getMethod("getSource");
            Method getPlayer = eventType.getMethod("getPlayer");
            Method getFormat = eventType.getMethod("getFormat");
            Method setColor = eventType.getMethod("setColor", int.class);
            Method append = getFormat.getReturnType().getMethod("append", Component.class);
            return Optional.of(new DamageNumberEventAdapter(eventType, getSource, getPlayer, getFormat, setColor, append));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Optional.empty();
        }
    }

    public Class<?> eventType() {
        return eventType;
    }

    public Object source(Object event) {
        return invoke(getSource, event);
    }

    public Object player(Object event) {
        return invoke(getPlayer, event);
    }

    public boolean apply(Object event, int color, Component component) {
        if (!eventType.isInstance(event) || component == null) {
            return false;
        }
        Object format = invoke(getFormat, event);
        if (format == null) {
            return false;
        }
        try {
            setColor.invoke(event, color);
            append.invoke(format, component);
            return true;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return false;
        }
    }

    private Object invoke(Method method, Object event) {
        if (!eventType.isInstance(event)) {
            return null;
        }
        try {
            return method.invoke(event);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return null;
        }
    }
}

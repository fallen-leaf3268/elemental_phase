package com.elementalphase.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Map;

public final class KubeJsValueConverter {
    private KubeJsValueConverter() {
    }

    public static JsonElement toJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement json) return json.deepCopy();
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, child) -> object.add(String.valueOf(key), toJson(child)));
            return object;
        }
        if (value instanceof Iterable<?> iterable) {
            JsonArray array = new JsonArray();
            iterable.forEach(child -> array.add(toJson(child)));
            return array;
        }
        if (value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            for (int i = 0; i < Array.getLength(value); i++) array.add(toJson(Array.get(value, i)));
            return array;
        }
        JsonElement rhino = rhino(value);
        if (rhino != null) return rhino;
        throw new IllegalArgumentException("Unsupported script value " + value.getClass().getName());
    }

    private static JsonElement rhino(Object value) {
        try {
            Method length = findOptional(value.getClass(), "getLength", 0);
            Method indexedGet = findIndexedGet(value.getClass());
            if (length != null && indexedGet != null) {
                int size = ((Number) length.invoke(value)).intValue();
                JsonArray array = new JsonArray();
                for (int index = 0; index < size; index++) {
                    array.add(toJson(indexedGet.invoke(value, index, value)));
                }
                return array;
            }
            Method getIds = value.getClass().getMethod("getIds");
            Object[] ids = (Object[]) getIds.invoke(value);
            Method get = findGet(value.getClass());
            JsonObject object = new JsonObject();
            for (Object id : ids) object.add(String.valueOf(id), toJson(get.invoke(value, String.valueOf(id), value)));
            return object;
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }

    private static Method findOptional(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        return null;
    }

    private static Method findIndexedGet(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == int.class) return method;
        }
        return null;
    }

    private static Method findGet(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == String.class) return method;
        }
        throw new NoSuchMethodException("get");
    }
}

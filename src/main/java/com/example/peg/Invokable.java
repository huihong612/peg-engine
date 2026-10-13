package com.example.peg;

import java.util.Arrays;
import java.util.stream.Collectors;

public interface Invokable {

    default void assertArgumentTypes(Object[] args, Class<?>... types) {
        if (args.length != types.length) {
            throw new IllegalArgumentException("Expected " + types.length + " arguments, but got " + args.length);
        }
        for (int i = 0; i < types.length; i++) {
            if (!isInstance(types[i], args[i])) {
                String expected = Arrays.stream(types).map(Class::getName).collect(Collectors.joining(", ", "[", "]"));
                String actual = Arrays.stream(args)
                        .map(a -> a == null ? "null" : a.getClass().getName())
                        .collect(Collectors.joining(", ", "[", "]"));
                throw new IllegalArgumentException("Expected " + expected + ", but got " + actual);
            }
        }
    }

    private boolean isInstance(Class<?> type, Object value) {
        if (type.isPrimitive()) {
            return isInstance(wrapPrimitiveType(type), value);
        } else {
            return type.isInstance(value);
        }
    }

    private Class<?> wrapPrimitiveType(Class<?> type) {
        return switch (type.getSimpleName()) {
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "char" -> Character.class;
            case "boolean" -> Boolean.class;
            default -> type;
        };
    }

    default Object invokeWithValue(PEGContext context, String name, Object[] args) {
        if ("value".equals(name)) {
            return value();
        } else {
            return invoke(context, name, args);
        }
    }

    Object invoke(PEGContext context, String name, Object[] args);

    Object value();
}

package com.example.peg;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Accessor {

    private final Object target;
    private final Map<String, Property> properties;
    private final Map<String, List<Method>> methods;

    public Accessor(Object target, Class<?> type) {
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");

        if (!type.isInstance(target)) {
            throw new IllegalArgumentException(
                    "Target of type " + target.getClass().getName() + " is not an instance of " + type.getName()
            );
        }

        // Public fields take precedence over JavaBean properties.
        Map<String, Property> propertyMap = Arrays.stream(type.getFields())
                .collect(Collectors.toMap(Field::getName, FieldProperty::new, (first, second) -> first));

        Arrays.stream(getBeanInfo(type).getPropertyDescriptors())
                .forEach(it -> propertyMap.putIfAbsent(it.getName(), new MethodProperty(it)));

        this.properties = Map.copyOf(propertyMap);

        this.methods = Arrays.stream(type.getMethods())
                .filter(method -> !method.isBridge())
                .filter(method -> !method.isSynthetic())
                .collect(Collectors.groupingBy(Method::getName));
    }

    private static BeanInfo getBeanInfo(Class<?> type) {
        try {
            return Introspector.getBeanInfo(type, Object.class);
        } catch (IntrospectionException e) {
            throw new IllegalArgumentException("Failed to inspect type: " + type.getName(), e);
        }
    }

    private Property lookupProperty(String name) {
        Property property = properties.get(name);

        if (property == null) {
            throw new IllegalArgumentException("No such property: " + name);
        }

        return property;
    }

    public void set(String name, Object value) {
        lookupProperty(name).set(target, value);
    }

    public Object get(String name) {
        return lookupProperty(name).get(target);
    }

    public Object call(String name, Object... args) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(args, "args");

        Method method = lookupMethod(name, args);

        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw propagateTargetException("Target method failed: " + name, e);
        } catch (ReflectiveOperationException e) {
            throw new AccessorException("Failed to invoke method: " + name, e);
        } catch (IllegalArgumentException e) {
            throw new AccessorException("Invalid arguments for method: " + name, e);
        }
    }

    private Method lookupMethod(String name, Object[] args) {
        List<Method> candidates = methods.get(name);

        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("No such method: " + name);
        }

        // Phase 1: exact runtime type matching.
        Method method = findUnique(candidates, candidate -> isExactMatch(candidate, args), name);

        if (method != null) {
            return method;
        }

        // Phase 2: compatible reference types and boxing matches.
        method = findUnique(candidates, candidate -> isCompatibleMatch(candidate, args), name);

        if (method != null) {
            return method;
        }

        throw new IllegalArgumentException("No matching method: " + name + " with " + args.length + " argument(s)");
    }

    private static Method findUnique(List<Method> candidates, Predicate<Method> predicate, String name) {
        List<Method> matches = candidates.stream().filter(predicate).toList();

        if (matches.size() > 1) {
            String signatures = matches.stream().map(Method::toGenericString).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Ambiguous method: " + name + ". Candidates: " + signatures);
        }

        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static boolean isExactMatch(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();

        if (types.length != args.length) {
            return false;
        }

        for (int i = 0; i < types.length; i++) {
            Object arg = args[i];
            if (arg == null || arg.getClass() != types[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean isCompatibleMatch(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();

        if (types.length != args.length) {
            return false;
        }

        for (int i = 0; i < types.length; i++) {
            if (!isInstanceOf(types[i], args[i])) {
                return false;
            }
        }

        return true;
    }

    public static Class<?> getPrimitiveWrapper(Class<?> type) {
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public static boolean isInstanceOf(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }

        if (type.isPrimitive()) {
            return getPrimitiveWrapper(type).isInstance(value);
        }

        return type.isInstance(value);
    }

    private static AccessorException propagateTargetException(String message, InvocationTargetException exception) {
        Throwable cause = exception.getCause();

        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        if (cause instanceof Error error) {
            throw error;
        }

        return new AccessorException(message, cause);
    }

    public static class AccessorException extends RuntimeException {

        public AccessorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private interface Property {

        void set(Object target, Object value);

        Object get(Object target);

        default void assertInstanceOf(Class<?> type, Object value) {
            if (!isInstanceOf(type, value)) {
                String actual = Optional.ofNullable(value).map(Object::getClass).map(Class::getName).orElse("null");
                throw new IllegalArgumentException("Expected " + type.getName() + ", but got " + actual);
            }
        }
    }

    private record FieldProperty(Field field) implements Property {

        @Override
        public void set(Object target, Object value) {
            assertInstanceOf(field.getType(), value);

            try {
                field.set(target, value);
            } catch (Exception e) {
                throw new AccessorException("Failed to set field: " + field.getName(), e);
            }
        }

        @Override
        public Object get(Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw new AccessorException("Failed to get field: " + field.getName(), e);
            }
        }
    }

    private record MethodProperty(PropertyDescriptor property) implements Property {

        @Override
        public void set(Object target, Object value) {
            Method setter = property.getWriteMethod();

            if (setter == null) {
                throw new IllegalArgumentException("Property does not support write: " + property.getName());
            }

            assertInstanceOf(setter.getParameterTypes()[0], value);

            try {
                setter.invoke(target, value);
            } catch (InvocationTargetException e) {
                throw propagateTargetException("Failed to set property: " + property.getName(), e);
            } catch (ReflectiveOperationException e) {
                throw new AccessorException("Failed to set property: " + property.getName(), e);
            }
        }

        @Override
        public Object get(Object target) {
            Method getter = property.getReadMethod();

            if (getter == null) {
                throw new IllegalArgumentException("Property does not support read: " + property.getName());
            }

            try {
                return getter.invoke(target);
            } catch (InvocationTargetException e) {
                throw propagateTargetException("Failed to get property: " + property.getName(), e);
            } catch (ReflectiveOperationException e) {
                throw new AccessorException("Failed to get property: " + property.getName(), e);
            }
        }
    }
}

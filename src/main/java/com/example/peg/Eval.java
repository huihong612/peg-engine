package com.example.peg;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Eval implements Invokable {

    private final String className;
    private final Object object;
    private final Map<String, Field> fields;
    private final Map<String, PropertyDescriptor> properties;
    private final Map<String, List<Method>> methods;

    public Eval(String className) {
        try {
            this.className = className;
            Class<?> type = Class.forName(className);
            object = type.getConstructor().newInstance();
            // 提取出类字段、属性、方法
            fields = Arrays.stream(type.getFields()).collect(Collectors.toMap(Field::getName, Function.identity()));
            properties = Arrays.stream(Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors())
                    .collect(Collectors.toMap(PropertyDescriptor::getName, Function.identity()));
            methods = Arrays.stream(type.getMethods()).collect(Collectors.groupingBy(Method::getName));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Failed to load type: '" + className + "'", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to create object: '" + className + "'", e);
        } catch (IntrospectionException e) {
            throw new IllegalArgumentException("Failed to resolve property: '" + className + "'", e);
        }
    }

    @Override
    public Object invoke(PEGContext context, String name, Object[] args) {
        char ch = name.charAt(0);
        if (ch == ':') {
            if (args.length == 0) {
                return invokeGetter(name.substring(1));
            } else {
                invokeSetter(name.substring(1), args[0]);
                return null;
            }
        } else {
            return invokeCall(context, name, args);
        }
    }

    private Object invokeGetter(String name) {
        // 查找字段
        Field field = fields.get(name);
        if (field != null) {
            try {
                return field.get(object);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to get field: '" + className + "." + name + "'", e);
            }
        }
        // 查找属性
        PropertyDescriptor property = properties.get(name);
        if (property != null) {
            if (property.getReadMethod() == null) {
                throw new IllegalArgumentException("Property is not readable: '" + className + "." + name + "'");
            }
            try {
                return property.getReadMethod().invoke(object);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Failed to read property: '" + className + "." + name + "'", e);
            }
        }
        // 没有找到属性
        throw new IllegalArgumentException("Undefined property: '" + className + "." + name + "'");
    }

    private void invokeSetter(String name, Object value) {
        // 查找字段
        Field field = fields.get(name);
        if (field != null) {
            try {
                field.set(object, value);
                return;
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to set field: '" + className + "." + name + "'", e);
            }
        }
        // 查找属性
        PropertyDescriptor property = properties.get(name);
        if (property != null) {
            if (property.getWriteMethod() == null) {
                throw new IllegalArgumentException("Property is not writeable: '" + className + "." + name + "'");
            }
            try {
                property.getWriteMethod().invoke(object, value);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Failed to write property: '" + className + "." + name + "'", e);
            }
        }
        // 没有找到属性
        throw new IllegalArgumentException("Undefined property: '" + className + "." + name + "'");
    }

    private Object invokeCall(PEGContext context, String name, Object[] args) {
        List<Method> candidate = methods.get(name);
        if (candidate == null) {
            throw new IllegalArgumentException("Method not found: '" + className + "." + name + "'");
        }
        List<Method> list = candidate.stream().filter(byArguments(args)).toList();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Method not found: '" + className + "." + name + "'");
        }
        if (list.size() > 1) {
            throw new IllegalArgumentException("Multiple methods found: '" + className + "." + name + "'");
        }
        try {
            Method method = list.getFirst();
            return method.invoke(object, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to invoke method: '" + className + "." + name + "'", e);
        }
    }

    private Predicate<Method> byArguments(Object[] args) {
        return method -> {
            if (method.getParameterCount() != args.length) {
                return false;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (!isInstance(types[i], args[i])) {
                    return false;
                }
            }
            return true;
        };
    }

    @Override
    public Object value() {
        return object;
    }
}

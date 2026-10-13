package com.example.peg;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Accessor {

    private static final Map<Class<?>, TypeMeta> TYPE_CACHE = new ConcurrentHashMap<>();

    private static final String GET_METHOD = "get";
    private static final String SET_METHOD = "set";

    private static TypeMeta getTypeMeta(Class<?> type) {
        TypeMeta cache = TYPE_CACHE.get(type);
        if (cache != null) {
            return cache;
        }
        TypeMeta typeMeta = new TypeMeta(type);
        TYPE_CACHE.put(type, typeMeta);
        return typeMeta;
    }

    /**
     * 设置对象属性值
     */
    public static void setProperty(Object target, String property, Object value) {
        if (target == null || property == null) {
            throw new IllegalArgumentException("Object and property cannot be null");
        }

        TypeMeta typeMeta = getTypeMeta(target.getClass());
        try {
            typeMeta.setProperty(target, property, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to set property: " + property, e);
        }
    }

    /**
     * 获取对象属性值
     */
    public static Object getProperty(Object target, String property) {
        if (target == null || property == null) {
            throw new IllegalArgumentException("Object and property cannot be null");
        }

        TypeMeta typeMeta = getTypeMeta(target.getClass());
        try {
            return typeMeta.getProperty(target, property);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to get property: " + property, e);
        }
    }

    /**
     * 通过索引设置值
     */
    public static void setIndex(Object target, Object[] indexes, Object value) {
        if (target == null || indexes == null) {
            throw new IllegalArgumentException("Object and index cannot be null");
        }

        TypeMeta typeMeta = getTypeMeta(target.getClass());
        try {
            typeMeta.setIndex(target, indexes, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set index", e);
        }
    }

    /**
     * 通过索引获取值
     */
    public static Object getIndex(Object target, Object[] indexes) {
        if (target == null || indexes == null) {
            throw new IllegalArgumentException("Object and index cannot be null");
        }

        TypeMeta typeMeta = getTypeMeta(target.getClass());
        try {
            return typeMeta.getIndex(target, indexes);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to get index", e);
        }
    }

    static class TypeMeta {

        private static final Map<Class<?>, Class<?>> BOX = Map.of(
                byte.class, Byte.class,
                short.class, Short.class,
                int.class, Integer.class,
                long.class, Long.class,
                float.class, Float.class,
                double.class, Double.class,
                boolean.class, Boolean.class,
                char.class, CharClass.class
        );

        private final Map<String, PropertyDescriptor> properties;
        private final Map<String, Field> fields;
        private final Set<Method> indexSetters;
        private final Set<Method> indexGetters;

        TypeMeta(Class<?> clazz) {
            this.properties = getProperties(clazz);
            this.fields = getFields(clazz);
            this.indexSetters = getIndexSetters(clazz);
            this.indexGetters = getIndexGetters(clazz);
        }

        private Map<String, PropertyDescriptor> getProperties(Class<?> c) {
            Map<String, PropertyDescriptor> map = new HashMap<>();
            try {
                BeanInfo beanInfo = Introspector.getBeanInfo(c, Object.class);
                for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                    map.put(pd.getName(), pd);
                }
            } catch (IntrospectionException e) {
                throw new RuntimeException("Failed to introspect class: " + c.getName(), e);
            }
            return map;
        }

        private Map<String, Field> getFields(Class<?> clazz) {
            return Arrays.stream(clazz.getFields()).collect(Collectors.toMap(Field::getName, Function.identity()));
        }

        private Set<Method> getIndexSetters(Class<?> clazz) {
            return Arrays.stream(clazz.getMethods()).filter(m -> SET_METHOD.equals(m.getName())).collect(Collectors.toSet());
        }

        private Set<Method> getIndexGetters(Class<?> clazz) {
            return Arrays.stream(clazz.getMethods()).filter(m -> GET_METHOD.equals(m.getName())).collect(Collectors.toSet());
        }

        private boolean isInstance(Class<?> clazz, Object value) {
            if (clazz.isPrimitive()) {
                if (value == null) return false;
                return BOX.get(clazz).isInstance(value);
            }
            return clazz.isInstance(value);
        }

        public void setProperty(Object target, String name, Object value) throws ReflectiveOperationException {
            Field field = fields.get(name);
            // 1. 尝试直接访问公共字段
            if (field != null && isInstance(field.getType(), value)) {
                field.set(target, value);
                return;
            }

            // 2. 使用内省机制查找 setter
            PropertyDescriptor pd = properties.get(name);
            if (pd != null && pd.getWriteMethod() != null && isInstance(pd.getPropertyType(), value)) {
                Method setter = pd.getWriteMethod();
                setter.invoke(target, value);
                return;
            }

            // 没有找到合适的属性
            throw new IllegalArgumentException("No accessible field or setter found for property: " + name);
        }

        public Object getProperty(Object target, String name) throws ReflectiveOperationException {
            Field field = fields.get(name);
            if (field != null) {
                return field.get(target);
            }

            PropertyDescriptor pd = properties.get(name);
            if (pd != null && pd.getReadMethod() != null) {
                return pd.getReadMethod().invoke(target);
            }

            throw new IllegalArgumentException("No accessible field or getter found for property: " + name);
        }

        public void setIndex(Object target, Object[] indexes, Object value) throws ReflectiveOperationException {
            // 找到候选方法
            List<Method> setters = indexSetters.stream().filter(m -> m.getParameterCount() == indexes.length + 1).filter(m -> {
                Class<?>[] types = m.getParameterTypes();
                for (int i = 0; i < indexes.length; i++) {
                    if (!isInstance(types[i], indexes[i])) {
                        return false;
                    }
                }
                return isInstance(types[indexes.length], value);
            }).toList();
            // 没找到或者找到多个方法
            if (setters.isEmpty()) {
                throw new IllegalArgumentException("No suitable set method found");
            }
            if (setters.size() > 1) {
                throw new IllegalArgumentException("More than one suitable set method found");
            }
            // 执行方法
            Object[] args = Arrays.copyOf(indexes, indexes.length + 1);
            args[indexes.length] = value;
            setters.get(0).invoke(target, args);
        }

        public Object getIndex(Object target, Object[] indexes) throws ReflectiveOperationException {
            // 找到候选方法
            List<Method> getters = indexGetters.stream().filter(m -> m.getParameterCount() == indexes.length).filter(m -> {
                Class<?>[] types = m.getParameterTypes();
                for (int i = 0; i < indexes.length; i++) {
                    if (!isInstance(types[i], indexes[i])) {
                        return false;
                    }
                }
                return true;
            }).toList();
            // 没找到或者找到多个方法
            if (getters.isEmpty()) {
                throw new IllegalArgumentException("No suitable get method found");
            }
            if (getters.size() > 1) {
                throw new IllegalArgumentException("More than one suitable get method found");
            }
            // 执行方法
            return getters.get(0).invoke(target, indexes);
        }
    }
}
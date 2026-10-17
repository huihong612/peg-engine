package com.example.peg.eval;

import com.example.peg.Invokable;
import com.example.peg.PEGContext;

public class Eval implements Invokable {

    private final Object target;
    private final Load load;

    public Eval(String className) {
        try {
            Class<?> type = Class.forName(className);
            target = type.getConstructor().newInstance();
            load = new Load(target, type);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Failed to load type: '" + className + "'", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to create object: '" + className + "'", e);
        }
    }

    @Override
    public Object invoke(PEGContext context, String name, Object[] args) {
        return load.dispatch(name, args);
    }

    @Override
    public Object value() {
        return target;
    }
}

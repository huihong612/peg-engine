package com.example.peg.eval;

import com.example.peg.Accessor;

public class Load {

    private final Accessor accessor;

    public Load(Object target, Class<?> type) {
        this.accessor = new Accessor(target, type);
    }

    public Object dispatch(String name, Object[] args) {
        if (name.charAt(0) != ':') {
            return accessor.call(name, args);
        }
        if (args.length == 0) {
            return accessor.get(name.substring(1));
        } else {
            accessor.set(name.substring(1), args[0]);
            return null;
        }
    }
}

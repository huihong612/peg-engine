package com.example.peg.eval;

import com.example.peg.Invokable;
import com.example.peg.PEGContext;

import java.util.ArrayList;
import java.util.List;

public class Array implements Invokable {

    private final List<Object> list = new ArrayList<>();

    @Override
    public Object invoke(PEGContext context, String name, Object[] args) {
        return switch (name) {
            case "set" -> {
                assertArgumentTypes(args, Integer.class, Object.class);
                int index = (int) args[0];

                Object oldValue = list.get(index);
                context.addUndo(() -> list.set(index, oldValue));

                list.set(index, args[1]);
                yield null;
            }
            case "get" -> {
                assertArgumentTypes(args, Integer.class);
                yield list.get((int) args[0]);
            }
            case "push" -> {
                assertArgumentTypes(args, Object.class);
                context.addUndo(list::removeLast);
                list.addLast(args[0]);
                yield null;
            }
            case "pop" -> {
                assertArgumentTypes(args);

                Object last = list.getLast();
                context.addUndo(() -> list.addLast(last));

                yield list.removeLast();
            }
            case "length" -> {
                assertArgumentTypes(args);
                yield list.size();
            }
            default -> throw new IllegalArgumentException("Unknown method: " + name);
        };
    }

    @Override
    public Object value() {
        return list.stream().map(this::unwrap).toList();
    }

    private Object unwrap(Object obj) {
        if (obj instanceof Invokable invokable) {
            return invokable.value();
        } else {
            return obj;
        }
    }
}

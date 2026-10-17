package com.example.peg.eval;

import com.example.peg.Invokable;
import com.example.peg.PEGContext;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class Dictionary implements Invokable {

    private final Map<String, Object> map = new HashMap<>();

    @Override
    public Object invoke(PEGContext context, String name, Object[] args) {
        return switch (name) {
            case "set" -> {
                assertArgumentTypes(args, String.class, Object.class);
                String key = (String) args[0];

                boolean existed = map.containsKey(key);
                Object oldValue = map.get(key);
                context.addUndo(() -> {
                    if (existed) {
                        map.put(key, oldValue);
                    } else {
                        map.remove(key);
                    }
                });

                map.put(key, args[1]);
                yield null;
            }
            case "get" -> {
                assertArgumentTypes(args, String.class);
                yield map.get((String) args[0]);
            }
            case "length" -> {
                assertArgumentTypes(args);
                yield map.size();
            }
            default -> throw new IllegalArgumentException("Unknown method: " + name);
        };
    }

    @Override
    public Object value() {
        return map.entrySet().stream().map(this::unwrap).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map.Entry<String, Object> unwrap(Map.Entry<String, Object> e) {
        if (e.getValue() instanceof Invokable invokable) {
            return Map.entry(e.getKey(), invokable.value());
        } else {
            return e;
        }
    }
}

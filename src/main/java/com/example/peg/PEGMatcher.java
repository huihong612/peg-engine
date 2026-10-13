package com.example.peg;

public final class PEGMatcher {
    private final PEGPattern pattern;
    private final String source;
    private Context ctx;
    private boolean ran;
    private boolean matched;

    PEGMatcher(PEGPattern p, String s) {
        pattern = p;
        source = s;
    }

    public boolean matches() {
        if (ran) return matched;
        ran = true;
        ctx = new Context(source, pattern.rules);

        Result r = callRule(pattern.entry);

        if (r.success() && ctx.pos == source.strip().length()) {
            matched = true;
            ctx.errors.error = null;
            ctx.undo.clear();
            return true;
        } else {
            ctx.rollback(new Checkpoint(0, 0));
            matched = false;
            return false;
        }
    }

    Result callRule(String name) {
        Expr e = ctx.rules.get(name);
        if (e == null) throw new GrammarCompileException("Undefined rule: " + name);

        Scope prev = ctx.scope;
        ctx.scope = new Scope(prev);

        ctx.ruleStack.push(name);
        Result r = e.eval(ctx);
        ctx.ruleStack.pop();

        if (r.success()) {
            ctx.scope = prev;
            return new Result(true, ctx.pos, r.value());
        } else {
            ctx.scope = prev;
            return Result.fail(ctx);
        }
    }

    public Object slot(String key) {
        if (!ran) matches();
        if (!matched) return null;

        Object value = ctx.slots.invoke(null, "get", new Object[]{key});
        if (value instanceof Invokable invokable) {
            return invokable.value();
        } else {
            return value;
        }
    }

    public ParseError error() {
        return ctx == null ? null : ctx.errors.error;
    }
}


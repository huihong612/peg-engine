package com.example.peg;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PEGPattern {
    final Map<String, Expr> rules;
    final String entry;

    private PEGPattern(Map<String, Expr> rules, String entry) {
        this.rules = Map.copyOf(rules);
        this.entry = entry;
    }

    public static PEGPattern compile(String template) {
        return compile(template, null);
    }

    public static PEGPattern compile(String template, String entryRule) {
        GrammarParser p = new GrammarParser(template);
        Map<String, Expr> rules = p.parse();
        String entry = entryRule == null ? rules.keySet().iterator().next() : entryRule;
        if (!rules.containsKey(entry)) throw new GrammarCompileException("Undefined entry rule: " + entry);
        return new PEGPattern(rules, entry);
    }

    public PEGMatcher matcher(String source) {
        return new PEGMatcher(this, source);
    }
}

interface Expr {
    Result eval(Context c);
}

record Result(boolean success, int position, Object value) {
    static Result ok(Context c, Object v) {
        return new Result(true, c.pos, v);
    }

    static Result fail(Context c) {
        return new Result(false, c.pos, null);
    }
}

final class Context implements PEGContext {
    final String source;
    int pos;
    Scope scope;
    Object returnValue;
    final Dictionary slots = new Dictionary();
    final Deque<Undo> undo = new ArrayDeque<>();
    final Map<String, Expr> rules;
    final Deque<String> ruleStack = new ArrayDeque<>();
    final ErrorTracker errors = new ErrorTracker();

    Context(String s, Map<String, Expr> r) {
        source = s;
        rules = r;
        scope = new Scope(null);
    }

    Checkpoint checkpoint() {
        return new Checkpoint(pos, undo.size());
    }

    void rollback(Checkpoint cp) {
        while (undo.size() > cp.undoSize()) undo.pop().undo();
        pos = cp.position();
    }

    Object get(String n) {
        return scope.get(n);
    }

    void bind(String n, Object v) {
        scope.locals.put(n, v);
    }

    void error(Set<String> expected) {
        errors.record(pos, expected, source);
    }

    @Override
    public void addUndo(Undo undo) {
        this.undo.addLast(undo);
    }
}

record Checkpoint(int position, int undoSize) {
}

final class Scope {
    final Map<String, Object> locals = new LinkedHashMap<>();
    final Scope parent;

    Scope(Scope p) {
        parent = p;
    }

    Object get(String n) {
        if (locals.containsKey(n)) return locals.get(n);
        if (parent != null) return parent.get(n);
        throw new MatchExecutionException("Undefined variable: " + n);
    }
}

record Empty() implements Expr {

    @Override
    public Result eval(Context c) {
        return Result.ok(c, null);
    }
}

record Literal(String s) implements Expr {

    public Result eval(Context c) {
        if (c.source.startsWith(s, c.pos)) {
            c.pos += s.length();
            return Result.ok(c, s);
        }
        c.error(Set.of('"' + s + '"'));
        return Result.fail(c);
    }
}

record CharClass(boolean neg, Set<Character> chars, List<int[]> ranges) implements Expr {

    public Result eval(Context c) {
        if (c.pos >= c.source.length()) {
            c.error(Set.of("character"));
            return Result.fail(c);
        }
        char x = c.source.charAt(c.pos);
        boolean yes = chars.contains(x);
        for (int[] a : ranges) yes |= x >= a[0] && x <= a[1];
        if (neg) yes = !yes;
        if (yes) {
            c.pos++;
            return Result.ok(c, String.valueOf(x));
        }
        c.error(Set.of("character class"));
        return Result.fail(c);
    }
}

record Sequence(List<Expr> xs) implements Expr {

    public Result eval(Context c) {
        int start = c.pos;
        for (Expr e : xs) {
            Result r = e.eval(c);
            if (!r.success()) return r;
        }
        return Result.ok(c, c.source.substring(start, c.pos));
    }
}

record Choice(List<Expr> xs) implements Expr {

    public Result eval(Context c) {
        for (Expr e : xs) {
            Checkpoint cp = c.checkpoint();
            Result r = e.eval(c);
            if (r.success()) return r;
            c.rollback(cp);
        }
        return Result.fail(c);
    }
}

record OptionalExpr(Expr e) implements Expr {

    public Result eval(Context c) {
        Checkpoint cp = c.checkpoint();
        Result r = e.eval(c);
        if (r.success()) return r;
        c.rollback(cp);
        return Result.ok(c, null);
    }
}

record Repeat(Expr e, boolean one) implements Expr {

    public Result eval(Context c) {
        int start = c.pos, n = 0;
        while (true) {
            Checkpoint cp = c.checkpoint();
            int before = c.pos;
            Result r = e.eval(c);
            if (!r.success()) {
                c.rollback(cp);
                break;
            }
            n++;
            if (c.pos == before) break;
        }
        if (one && n == 0) return Result.fail(c);
        return Result.ok(c, c.source.substring(start, c.pos));
    }
}

record AndPred(Expr e) implements Expr {

    public Result eval(Context c) {
        Checkpoint cp = c.checkpoint();
        Result r = e.eval(c);
        c.rollback(cp);
        return r.success() ? Result.ok(c, null) : Result.fail(c);
    }
}

record NotPred(Expr e) implements Expr {

    public Result eval(Context c) {
        Checkpoint cp = c.checkpoint();
        Result r = e.eval(c);
        c.rollback(cp);
        return r.success() ? Result.fail(c) : Result.ok(c, null);
    }
}

record RuleRefExpr(String n) implements Expr {

    public Result eval(Context c) {
        Expr e = c.rules.get(n);
        if (e == null) {
            throw new GrammarCompileException("Undefined rule: " + n);
        }

        Scope prev = c.scope;
        Object prevRet = c.returnValue;
        c.scope = new Scope(prev);
        c.returnValue = null;

        c.ruleStack.push(n);
        Result r = e.eval(c);
        Object v = c.returnValue != null ? c.returnValue : r.value();
        c.ruleStack.pop();

        if (r.success()) {
            c.returnValue = prevRet;
            c.scope = prev;
            return Result.ok(c, v);
        } else {
            c.returnValue = prevRet;
            c.scope = prev;
            return Result.fail(c);
        }
    }
}

record Capture(String name, Expr e) implements Expr {

    public Result eval(Context c) {
        Result r = e.eval(c);
        if (r.success()) c.bind(name, r.value());
        return r;
    }
}

abstract class Action implements Expr {
    public Result eval(Context c) {
        var ret = run(c);
        return Result.ok(c, ret);
    }

    abstract Object run(Context c);
}

final class VarAction extends Action {
    final String n;
    final String kind;

    VarAction(String n, String k) {
        this.n = n;
        kind = k;
    }

    Object run(Context c) {
        Object v = switch (kind) {
            case "dict" -> new Dictionary();
            case "list" -> new Array();
            default -> throw new IllegalArgumentException("Undefined variable: " + kind);
        };
        c.bind(n, v);
        return null;
    }
}

record NameValue(String n) implements Expr {
    public Result eval(Context c) {
        return Result.ok(c, c.get(n));
    }
}

record ConstExpr(Object v) implements Expr {
    public Result eval(Context c) {
        return Result.ok(c, v);
    }
}

record SlotExpr() implements Expr {
    public Result eval(Context c) {
        return Result.ok(c, c.slots);
    }
}

final class MethodAction extends Action {
    final Expr base;
    final String method;
    final List<Expr> args;

    MethodAction(Expr b, String m, List<Expr> a) {
        base = b;
        method = m;
        args = a;
    }

    Object run(Context c) {
        Object b = base.eval(c).value();
        if (b instanceof Invokable invokable) {
            Object[] args = this.args.stream().map(a -> a.eval(c).value()).toArray();
            return invokable.invokeWithValue(c, method, args);
        } else {
            throw new MatchExecutionException("Method target is not invokable");
        }
    }
}

final class ReturnAction extends Action {
    final Expr e;

    ReturnAction(Expr x) {
        e = x;
    }

    Object run(Context c) {
        c.returnValue = e.eval(c).value();
        return null;
    }
}

final class ErrorTracker {
    ParseError error;

    void record(int pos, Set<String> exp, String src) {
        if (error == null || pos > error.position()) {
            int line = (int) (1 + src.substring(0, pos).chars().filter(x -> x == '\n').count());
            int column = 1 + pos - (src.lastIndexOf('\n', Math.max(0, pos - 1)) + 1);
            var expected = new LinkedHashSet<>(exp);
            var actual = pos < src.length() ? "\"" + src.charAt(pos) + "\"" : "<EOF>";
            error = new ParseError(pos, line, column, expected, actual);
        } else if (pos == error.position()) {
            error.expected().addAll(exp);
        }
    }
}

final class GrammarParser {
    final String s;
    int p;
    boolean newlineBefore;
    final Map<String, Expr> rules = new LinkedHashMap<>();

    GrammarParser(String s) {
        this.s = s;
    }

    Map<String, Expr> parse() {
        String[] lines = s.split("\\R", -1);
        String current = null;
        StringBuilder rhs = new StringBuilder();
        Pattern keyValuePattern = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)\\s*$");

        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            Matcher m = keyValuePattern.matcher(line);
            if (m.matches()) {
                if (current != null) rules.put(current, new GrammarParser(rhs.toString()).choice());
                current = m.group(1);
                rhs.setLength(0);
                rhs.append(m.group(2));
            } else {
                if (current == null) throw new GrammarCompileException("Expected rule at: " + line);
                rhs.append(" ").append(t);
            }
        }
        if (current != null) rules.put(current, new GrammarParser(rhs.toString()).choice());
        if (rules.isEmpty()) throw new GrammarCompileException("No rules");
        return rules;
    }

    Expr choice() {
        List<Expr> x = new ArrayList<>();
        x.add(sequence());
        skip();
        while (peek('/')) {
            p++;
            x.add(sequence());
            skip();
        }
        return x.size() == 1 ? x.getFirst() : new Choice(x);
    }

    Expr sequence() {
        List<Expr> x = new ArrayList<>();
        while (true) {
            skip();
            if (eof() || peek(')') || peek('/') || peek('}') || (!x.isEmpty() && isRuleStart())) break;
            x.add(postfix());
        }
        if (x.isEmpty()) return new Empty();
        return x.size() == 1 ? x.getFirst() : new Sequence(x);
    }

    boolean isRuleStart() {
        if (!newlineBefore) return false;
        int q2 = p;
        while (q2 < s.length() && (Character.isLetterOrDigit(s.charAt(q2)) || s.charAt(q2) == '_')) q2++;
        while (q2 < s.length() && Character.isWhitespace(s.charAt(q2))) q2++;
        return q2 < s.length() && s.charAt(q2) == '=';
    }

    Expr postfix() {
        Expr e = primary();
        skip();
        if (peek('?')) {
            p++;
            return new OptionalExpr(e);
        }
        if (peek('*')) {
            p++;
            return new Repeat(e, false);
        }
        if (peek('+')) {
            p++;
            return new Repeat(e, true);
        }
        return e;
    }

    Expr primary() {
        skip();
        if (peek('"') || peek('\'')) {
            return new Literal(string());
        }
        if (peek('[')) {
            return charClass();
        }
        if (match("(")) {
            skip();
            if (match("?<")) {
                String n = ident();
                skip();
                expect('>');
                skip();
                Expr e = choice();
                skip();
                expect(')');
                return new Capture(n, e);
            }
            Expr e = choice();
            expect(')');
            return e;
        }
        if (match("&")) {
            return new AndPred(primary());
        }
        if (match("!")) {
            return new NotPred(primary());
        }
        if (peek('{')) {
            return actions();
        }
        return new RuleRefExpr(ident());
    }

    Expr actions() {
        expect('{');
        skip();
        List<Expr> x = new ArrayList<>();
        while (!match("}")) {
            Expr a = action();
            if (!(a instanceof Empty)) x.add(a);
            skip();
        }
        if (x.isEmpty()) return new Empty();
        return x.size() == 1 ? x.getFirst() : new Sequence(x);
    }

    Expr action() {
        skip();
        if (match(";")) {
            return new Empty();
        }
        // var <ident> = <ident>()
        if (match("var")) {
            String n = ident();
            skip();
            expect('=');
            String fn = ident();
            skip();
            expect('(');
            expect(')');
            return new VarAction(n, fn);
        }
        // return actionExpr
        if (match("return")) {
            Expr e = actionExpr();
            return new ReturnAction(e);
        }

        String lhs = ident();
        skip();
        // <ident> [ <actionExpr> ] = <actionExpr>
        if (match("[")) {
            Expr k = actionExpr();
            skip();
            expect(']');
            skip();
            if (match("=")) {
                Expr v = actionExpr();
                Expr base = lhs.equals("slot") ? new SlotExpr() : new NameValue(lhs);
                return new MethodAction(base, "set", List.of(k, v));
            }
            throw new GrammarCompileException("Only indexed assignment supported");
        }
        // <ident> . <ident> ( <args> )
        if (match(".")) {
            String method = ident();
            List<Expr> args = args();
            return new MethodAction(new NameValue(lhs), method, args);
        }
        throw new GrammarCompileException("Invalid action at " + p);
    }

    List<Expr> args() {
        skip();
        expect('(');
        skip();
        List<Expr> a = new ArrayList<>();
        if (!match(")")) {
            a.add(actionExpr());
            skip();
            while (match(",")) {
                skip();
                a.add(actionExpr());
                skip();
            }
            expect(')');
        }
        return a;
    }

    Expr actionExpr() {
        skip();
        // 识别字符串
        if (peek('"') || peek('\'')) {
            return new ConstExpr(string());
        }
        // 识别数值
        if (p < s.length() && (Character.isDigit(s.charAt(p)) || (s.charAt(p) == '-' && p + 1 < s.length() && Character.isDigit(s.charAt(p + 1))))) {
            int st = p++;
            while (p < s.length() && Character.isDigit(s.charAt(p))) {
                p++;
            }
            return new ConstExpr(s.substring(st, p));
        }
        String n = ident();
        skip();
        // 方法调用
        if (match(".")) {
            String m = ident();
            List<Expr> a = args();
            return new MethodAction(new NameValue(n), m, a);
        }
        // 对象索引
        if (match("[")) {
            Expr k = actionExpr();
            expect(']');
            Expr b = n.equals("slot") ? new SlotExpr() : new NameValue(n);
            return new MethodAction(b, "get", List.of(k));
        }
        // 变量获取
        return new NameValue(n);
    }

    String ident() {
        skip();
        int st = p;
        if (p < s.length() && (Character.isLetter(s.charAt(p)) || s.charAt(p) == '_')) {
            do {
                p++;
            } while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_'));
            return s.substring(st, p);
        }
        throw new GrammarCompileException("Expected identifier at " + p);
    }

    String string() {
        char q = s.charAt(p++);
        StringBuilder b = new StringBuilder();
        while (p < s.length() && s.charAt(p) != q) {
            char c = s.charAt(p++);
            if (c == '\\' && p < s.length()) {
                char n = s.charAt(p++);
                b.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case '\'' -> '\'';
                    default -> n;
                });
            } else b.append(c);
        }
        expect(q);
        return b.toString();
    }

    Expr charClass() {
        expect('[');
        boolean neg = false;
        if (peek('^')) {
            neg = true;
            p++;
        }
        Set<Character> ch = new HashSet<>();
        List<int[]> rs = new ArrayList<>();
        while (!peek(']')) {
            char a = classChar();
            if (peek('-') && p + 1 < s.length() && s.charAt(p + 1) != ']') {
                p++;
                char z = classChar();
                rs.add(new int[]{a, z});
            } else ch.add(a);
        }
        expect(']');
        return new CharClass(neg, ch, rs);
    }

    char classChar() {
        char c = s.charAt(p++);
        if (c == '\\' && p < s.length()) return s.charAt(p++);
        return c;
    }

    void skip() {
        boolean saw = false;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
            saw = true;
            if (s.charAt(p) == '\n') newlineBefore = true;
            p++;
        }
        if (!saw) newlineBefore = false;
    }

    boolean peek(char c) {
        return p < s.length() && s.charAt(p) == c;
    }

    boolean match(String x) {
        if (s.startsWith(x, p)) {
            p += x.length();
            return true;
        }
        return false;
    }

    void expect(char c) {
        if (!peek(c)) throw new GrammarCompileException("Expected '" + c + "' at " + p);
        p++;
    }

    boolean eof() {
        return p >= s.length();
    }
}

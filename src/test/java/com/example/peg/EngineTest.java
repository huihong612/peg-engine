package com.example.peg;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    @Test
    void basics() {
        var p = PEGPattern.compile("number = [0-9]+");
        assertTrue(p.matcher("123").matches());
        assertFalse(p.matcher("123x").matches());
    }

    @Test
    void array() {
        PEGPattern pattern = PEGPattern.compile("""
                array = { var a = list() } "[" number ( "," number )* "]" { slot['r'] = a }
                number = (?<value>[0-9]+) { a.push(value) }
                """);
        PEGMatcher matcher = pattern.matcher("[1,2,3]");
        assertTrue(matcher.matches());
        assertEquals(List.of("1", "2", "3"), matcher.slot("r"));
    }

    @Test
    void captureAndReturn() {
        var p = PEGPattern.compile("number = (?<out num>[0-9]+)");
        PEGMatcher matcher = p.matcher("123");
        assertTrue(matcher.matches());
    }

    @Test
    void slotMutation() {
        String g = """
                s = "a" { slot["r"] = 7 }
                """;
        var m = PEGPattern.compile(g).matcher("a");
        assertTrue(m.matches());
        assertEquals("7", m.slot("r"));
    }

    @Test
    void choiceRollbackSlot() {
        String g = """
                test = A / B
                A = "abc" { slot["x"] = 1 } "def"
                B = "abcxyz"
                """;
        var m = PEGPattern.compile(g).matcher("abcxyz");
        assertTrue(m.matches());
        assertNull(m.slot("x"));
    }

    @Test
    void dynamicScopeMap() {
        String g = """
                outer = { var obj = dict() } inner { return obj }
                inner = { obj["x"] = 1 }
                """;
        var p = PEGPattern.compile(g, "outer");
        var m = p.matcher("");
        assertTrue(m.matches());
    }

    @Test
    void recursiveJson() {
        String g = """
                value = (?<out r>object / array / string / number) { slot['r'] = r }
                object = { var obj = dict() } "{" (pair ("," pair)*)? "}" { return obj }
                pair = (?<k>string) ":" (?<v>value) { obj[k] = v }
                array = { var arr = list() } "[" ((?<v>value) { arr.push(v) } ("," (?<v>value) { arr.push(v) })*)? "]" { return arr }
                string = '"' (?<out v>[^"]*) '"'
                number = (?<out v>[0-9]+)
                """;
        var m = PEGPattern.compile(g).matcher("""
                {"a":1,"b":[2,3]}
                """);
        assertTrue(m.matches(), String.valueOf(m.error()));
        assertEquals(Map.of("a", "1", "b", List.of("2", "3")), m.slot("r"));
    }

    @Test
    void calc() {
        String g = """
                expr    = { var op = list() } sum { slot['op'] = op }
                sum     = product ('+' product { op.push('add') } / '-' product { op.push('sub') })*
                product = unary ('*' unary { op.push('mul') } / '/' unary { op.push('div') })*
                unary   = '+' unary / '-' unary { op.push('not') } / primary
                primary = number / '(' sum ')'
                number  = (?<v>[0-9]+ ('.' [0-9]+)?) { op.push(v) }
                """;
        PEGMatcher matcher = PEGPattern.compile(g).matcher("1.5+2.5");
        assertTrue(matcher.matches());
        Object op = matcher.slot("op");
        assertNotNull(op);
        assertInstanceOf(List.class, op);
        assertEquals(List.of("1.5", "2.5", "add"), op);
        assertEquals(new BigDecimal("4.0"), calc((List<?>) op));
    }

    private BigDecimal calc(List<?> op) {
        var queue = new ArrayDeque<>((List<?>) op);
        var data = new ArrayDeque<BigDecimal>();
        while (!queue.isEmpty()) {
            String value = queue.poll().toString();
            BigDecimal ret = switch (value) {
                case "not" -> data.pop().negate();
                case "add" -> calc(data, BigDecimal::add);
                case "sub" -> calc(data, BigDecimal::subtract);
                case "mul" -> calc(data, BigDecimal::multiply);
                case "div" -> calc(data, (a, b) -> a.divide(b, MathContext.DECIMAL64));
                default -> new BigDecimal(value);
            };
            data.push(ret);
        }
        return data.pop();
    }

    private BigDecimal calc(Deque<BigDecimal> d, BiFunction<BigDecimal, BigDecimal, BigDecimal> op) {
        BigDecimal b = d.pop();
        BigDecimal a = d.pop();
        return op.apply(a, b);
    }

    @Test
    void errorReporting() {
        var m = PEGPattern.compile("n = [0-9]+").matcher("abc");
        assertFalse(m.matches());
        assertNotNull(m.error());
        assertEquals(1, m.error().line());
        assertEquals(1, m.error().column());
    }
}

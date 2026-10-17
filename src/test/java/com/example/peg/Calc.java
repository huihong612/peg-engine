package com.example.peg;

import com.example.peg.eval.Load;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.Deque;

public class Calc implements Invokable {

    private final Deque<BigDecimal> stack = new ArrayDeque<>();
    private final Load load = new Load(this, Calc.class);

    @Override
    public Object invoke(PEGContext context, String name, Object[] args) {
        return load.dispatch(name, args);
    }

    public void push(String s) {
        stack.push(new BigDecimal(s));
    }

    public void print(Object o) {
        System.out.println(o);
    }

    public void negate() {
        BigDecimal value = stack.pop();
        stack.push(value.negate());
    }

    public void add() {
        BigDecimal b = stack.pop();
        BigDecimal a = stack.pop();
        stack.push(a.add(b));
    }

    public void sub() {
        BigDecimal b = stack.pop();
        BigDecimal a = stack.pop();
        stack.push(a.subtract(b));
    }

    public void mul() {
        BigDecimal b = stack.pop();
        BigDecimal a = stack.pop();
        stack.push(a.multiply(b));
    }

    public void div() {
        BigDecimal b = stack.pop();
        BigDecimal a = stack.pop();
        stack.push(a.divide(b, MathContext.DECIMAL64));
    }

    @Override
    public Object value() {
        return stack.peek();
    }
}
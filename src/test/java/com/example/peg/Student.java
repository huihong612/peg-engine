package com.example.peg;

import java.text.MessageFormat;

public class Student {
    private String name;
    public int age;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String say() {
        return MessageFormat.format("name={0}, age={1}", name, age);
    }

    public int parseInt(String str) {
        return Integer.parseInt(str);
    }
}
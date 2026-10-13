package com.example.peg;

import java.util.Set;

public record ParseError(int position, int line, int column, Set<String> expected, String actual) {
}

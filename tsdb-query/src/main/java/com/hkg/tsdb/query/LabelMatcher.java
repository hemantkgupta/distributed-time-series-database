package com.hkg.tsdb.query;

import com.hkg.tsdb.common.LabelSet;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PromQL-style label matcher subset: =, !=, =~, !~.
 */
public record LabelMatcher(String label, Operator operator, String value) {

    public enum Operator {
        EQ, NEQ, REGEX, NOT_REGEX
    }

    public LabelMatcher {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
        if (label.isEmpty()) throw new IllegalArgumentException("label must not be empty");
    }

    public static LabelMatcher eq(String label, String value) {
        return new LabelMatcher(label, Operator.EQ, value);
    }

    public static LabelMatcher neq(String label, String value) {
        return new LabelMatcher(label, Operator.NEQ, value);
    }

    public static LabelMatcher regex(String label, String value) {
        return new LabelMatcher(label, Operator.REGEX, value);
    }

    public static LabelMatcher notRegex(String label, String value) {
        return new LabelMatcher(label, Operator.NOT_REGEX, value);
    }

    public boolean matches(LabelSet labels) {
        String actual = labels.get(label);
        return switch (operator) {
            case EQ -> value.equals(actual);
            case NEQ -> !value.equals(actual);
            case REGEX -> actual != null && Pattern.matches(value, actual);
            case NOT_REGEX -> actual == null || !Pattern.matches(value, actual);
        };
    }
}

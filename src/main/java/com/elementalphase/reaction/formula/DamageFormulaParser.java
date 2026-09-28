package com.elementalphase.reaction.formula;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DamageFormulaParser {
    private static final Pattern NUMBER = Pattern.compile("(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final int MAX_SOURCE_LENGTH = 4096;
    private static final int MAX_NODES = 512;
    private static final int MAX_NESTING = 64;

    public static DamageFormula parse(String source) {
        ReactionFormula formula = parseReaction(source);
        return (originalDamage, scale) -> formula.evaluate(ReactionFormulaContext.legacy(originalDamage, scale));
    }

    public static ReactionFormula parseReaction(String source) {
        return new Parser(source).parse();
    }

    public static ReactionFormula parseReaction(String source, Set<String> allowedVariables,
                                                 Set<String> allowedFunctions, boolean allowComparisons) {
        return new Parser(source, Set.copyOf(allowedVariables), Set.copyOf(allowedFunctions), allowComparisons).parse();
    }

    public static final class FormulaParseException extends IllegalArgumentException {
        public FormulaParseException(int position, String message) {
            super("公式在位置 " + position + " 处" + message);
        }
    }

    private static final class Parser {
        private final String source;
        private final Set<String> allowedVariables;
        private final Set<String> allowedFunctions;
        private final boolean allowComparisons;
        private int position;
        private int nodeCount;
        private int nesting;

        private Parser(String source) {
            this(source, null, null, true);
        }

        private Parser(String source, Set<String> allowedVariables, Set<String> allowedFunctions, boolean allowComparisons) {
            this.allowedVariables = allowedVariables;
            this.allowedFunctions = allowedFunctions;
            this.allowComparisons = allowComparisons;
            this.source = Objects.requireNonNull(source, "source");
            if (source.length() > MAX_SOURCE_LENGTH) {
                throw new FormulaParseException(MAX_SOURCE_LENGTH, "长度超过上限 " + MAX_SOURCE_LENGTH);
            }
        }

        private ReactionFormula parse() {
            Node result = comparison();
            skipWhitespace();
            if (!atEnd()) {
                fail("存在尾随内容");
            }
            return result;
        }

        private Node comparison() {
            Node left = expression();
            skipWhitespace();
            Operation operation = comparisonOperation();
            if (operation == null) {
                return left;
            }
            if (!allowComparisons) fail("此用途不支持比较运算");
            Node result = node(new BinaryNode(operation, left, expression()));
            skipWhitespace();
            if (comparisonOperation() != null) {
                fail("不支持连续比较");
            }
            return result;
        }

        private Node expression() {
            Node result = term();
            while (true) {
                skipWhitespace();
                if (consume('+')) {
                    result = node(new BinaryNode(Operation.ADD, result, term()));
                } else if (consume('-')) {
                    result = node(new BinaryNode(Operation.SUBTRACT, result, term()));
                } else {
                    return result;
                }
            }
        }

        private Node term() {
            Node result = unary();
            while (true) {
                skipWhitespace();
                if (consume('*')) {
                    result = node(new BinaryNode(Operation.MULTIPLY, result, unary()));
                } else if (consume('/')) {
                    int divisorPosition = nextNonWhitespacePosition();
                    Node divisor = unary();
                    if (divisor.constantValue().isPresent() && divisor.constantValue().getAsDouble() == 0.0D) {
                        throw new FormulaParseException(divisorPosition, "除数不能是常量零");
                    }
                    result = node(new BinaryNode(Operation.DIVIDE, result, divisor));
                } else {
                    return result;
                }
            }
        }

        private Node unary() {
            skipWhitespace();
            if (consume('+')) {
                return unaryOperand(false);
            }
            if (consume('-')) {
                return unaryOperand(true);
            }
            return primary();
        }

        private Node unaryOperand(boolean negate) {
            enterNesting();
            try {
                return node(new UnaryNode(negate, unary()));
            } finally {
                nesting--;
            }
        }

        private Node node(Node node) {
            if (++nodeCount > MAX_NODES) {
                fail("节点数量超过上限 " + MAX_NODES);
            }
            return node;
        }

        private void enterNesting() {
            if (++nesting > MAX_NESTING) {
                fail("嵌套深度超过上限 " + MAX_NESTING);
            }
        }

        private Node primary() {
            skipWhitespace();
            if (atEnd()) {
                fail("缺少表达式");
            }
            if (consume('(')) {
                enterNesting();
                try {
                    Node result = comparison();
                    expect(')');
                    return result;
                } finally {
                    nesting--;
                }
            }
            if (isNumberStart()) {
                return number();
            }
            if (isIdentifierStart(current())) {
                int identifierPosition = position;
                String identifier = identifier();
                VariableNode variable = VariableNode.fromName(identifier);
                if (variable != null) {
                    if (allowedVariables != null && !allowedVariables.contains(identifier)) fail("此用途不支持变量 " + identifier);
                    return node(variable);
                }
                Function function = Function.fromName(identifier);
                if (function != null) {
                    if (allowedFunctions != null && !allowedFunctions.contains(identifier)) fail("此用途不支持函数 " + identifier);
                    skipWhitespace();
                    if (!consume('(')) {
                        throw new FormulaParseException(position, "函数缺少左括号");
                    }
                    enterNesting();
                    try {
                        return function(function);
                    } finally {
                        nesting--;
                    }
                }
                throw new FormulaParseException(identifierPosition, "未知标识符 '" + identifier + "'");
            }
            fail("无法识别的表达式");
            throw new AssertionError();
        }

        private Node number() {
            int start = position;
            Matcher matcher = NUMBER.matcher(source);
            matcher.region(position, source.length());
            if (!matcher.lookingAt()) {
                fail("无效数字");
            }
            position = matcher.end();
            String literal = source.substring(start, position);
            try {
                double value = Double.parseDouble(literal);
                if (!Double.isFinite(value)) {
                    throw new FormulaParseException(start, "数字必须是有限值");
                }
                return node(new ConstantNode(value));
            } catch (NumberFormatException exception) {
                throw new FormulaParseException(start, "无效数字");
            }
        }

        private Node function(Function function) {
            java.util.ArrayList<Node> arguments = new java.util.ArrayList<>(function.argumentCount);
            for (int index = 0; index < function.argumentCount; index++) {
                if (index > 0) {
                    expect(',');
                }
                arguments.add(comparison());
            }
            expect(')');
            return node(new FunctionNode(function, arguments));
        }

        private Operation comparisonOperation() {
            if (consume(">=")) return Operation.GREATER_OR_EQUAL;
            if (consume("<=")) return Operation.LESS_OR_EQUAL;
            if (consume("==")) return Operation.EQUAL;
            if (consume("!=")) return Operation.NOT_EQUAL;
            if (consume('>')) return Operation.GREATER;
            if (consume('<')) return Operation.LESS;
            return null;
        }

        private String identifier() {
            int start = position;
            position++;
            while (!atEnd() && isIdentifierPart(current())) {
                position++;
            }
            return source.substring(start, position);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) {
                fail("缺少 '" + expected + "'");
            }
        }

        private boolean isNumberStart() {
            return Character.isDigit(current()) || current() == '.' && position + 1 < source.length()
                    && Character.isDigit(source.charAt(position + 1));
        }

        private int nextNonWhitespacePosition() {
            skipWhitespace();
            return position;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(current())) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && current() == expected) {
                position++;
                return true;
            }
            return false;
        }

        private boolean consume(String expected) {
            if (source.startsWith(expected, position)) {
                position += expected.length();
                return true;
            }
            return false;
        }

        private boolean atEnd() {
            return position >= source.length();
        }

        private char current() {
            return source.charAt(position);
        }

        private void fail(String message) {
            throw new FormulaParseException(position, message);
        }

        private static boolean isIdentifierStart(char character) {
            return character == '_' || Character.isLetter(character);
        }

        private static boolean isIdentifierPart(char character) {
            return isIdentifierStart(character) || Character.isDigit(character);
        }
    }

    private interface Node extends ReactionFormula {
        OptionalDouble constantValue();
    }

    private record ConstantNode(double value) implements Node {
        @Override
        public double evaluate(ReactionFormulaContext context) {
            return value;
        }

        @Override
        public OptionalDouble constantValue() {
            return OptionalDouble.of(value);
        }
    }

    private enum VariableNode implements Node {
        ORIGINAL_DAMAGE("original_damage", ReactionFormulaContext::originalDamage),
        CURRENT_DAMAGE("current_damage", ReactionFormulaContext::currentDamage),
        SCALE("scale", ReactionFormulaContext::scale),
        TRIGGER_AMOUNT("trigger_amount", ReactionFormulaContext::triggerAmount),
        AURA_AMOUNT("aura_amount", ReactionFormulaContext::auraAmount),
        CONSUMED_TRIGGER("consumed_trigger", ReactionFormulaContext::consumedTrigger),
        CONSUMED_AURA("consumed_aura", ReactionFormulaContext::consumedAura),
        REMAINING_TRIGGER("remaining_trigger", ReactionFormulaContext::remainingTrigger),
        REMAINING_AURA("remaining_aura", ReactionFormulaContext::remainingAura),
        ATTACKER_LEVEL("attacker_level", ReactionFormulaContext::attackerLevel),
        ELEMENT_STRENGTH("element_strength", ReactionFormulaContext::elementStrength),
        TARGET_HEALTH("target_health", ReactionFormulaContext::targetHealth),
        TARGET_MAX_HEALTH("target_max_health", ReactionFormulaContext::targetMaxHealth),
        TARGET_HEALTH_RATIO("target_health_ratio", ReactionFormulaContext::targetHealthRatio),
        TARGET_RESISTANCE("target_resistance", ReactionFormulaContext::targetResistance),
        DISTANCE("distance", ReactionFormulaContext::distance),
        RADIUS("radius", ReactionFormulaContext::radius);

        private final String name;
        private final java.util.function.ToDoubleFunction<ReactionFormulaContext> getter;

        VariableNode(String name, java.util.function.ToDoubleFunction<ReactionFormulaContext> getter) {
            this.name = name;
            this.getter = getter;
        }

        private static VariableNode fromName(String name) {
            for (VariableNode value : values()) {
                if (value.name.equals(name)) return value;
            }
            return null;
        }

        @Override
        public double evaluate(ReactionFormulaContext context) {
            return getter.applyAsDouble(context);
        }

        @Override
        public OptionalDouble constantValue() {
            return OptionalDouble.empty();
        }
    }

    private record UnaryNode(boolean negate, Node operand) implements Node {
        @Override
        public double evaluate(ReactionFormulaContext context) {
            double value = operand.evaluate(context);
            return negate ? -value : value;
        }

        @Override
        public OptionalDouble constantValue() {
            OptionalDouble value = operand.constantValue();
            return value.isPresent() ? OptionalDouble.of(negate ? -value.getAsDouble() : value.getAsDouble()) : value;
        }
    }

    private record BinaryNode(Operation operation, Node left, Node right) implements Node {
        @Override
        public double evaluate(ReactionFormulaContext context) {
            return operation.apply(left.evaluate(context), right.evaluate(context));
        }

        @Override
        public OptionalDouble constantValue() {
            OptionalDouble leftValue = left.constantValue();
            OptionalDouble rightValue = right.constantValue();
            return leftValue.isPresent() && rightValue.isPresent()
                    ? OptionalDouble.of(operation.apply(leftValue.getAsDouble(), rightValue.getAsDouble()))
                    : OptionalDouble.empty();
        }
    }

    private record FunctionNode(Function function, List<Node> arguments) implements Node {
        private FunctionNode {
            arguments = List.copyOf(arguments);
        }

        @Override
        public double evaluate(ReactionFormulaContext context) {
            return function.apply(arguments, context);
        }

        @Override
        public OptionalDouble constantValue() {
            double[] values = new double[arguments.size()];
            for (int index = 0; index < arguments.size(); index++) {
                OptionalDouble value = arguments.get(index).constantValue();
                if (value.isEmpty()) {
                    return OptionalDouble.empty();
                }
                values[index] = value.getAsDouble();
            }
            return OptionalDouble.of(function.apply(values));
        }
    }

    private enum Operation {
        ADD(Double::sum),
        SUBTRACT((left, right) -> left - right),
        MULTIPLY((left, right) -> left * right),
        DIVIDE((left, right) -> left / right),
        GREATER((left, right) -> left > right ? 1.0D : 0.0D),
        GREATER_OR_EQUAL((left, right) -> left >= right ? 1.0D : 0.0D),
        LESS((left, right) -> left < right ? 1.0D : 0.0D),
        LESS_OR_EQUAL((left, right) -> left <= right ? 1.0D : 0.0D),
        EQUAL((left, right) -> Double.compare(left, right) == 0 ? 1.0D : 0.0D),
        NOT_EQUAL((left, right) -> Double.compare(left, right) != 0 ? 1.0D : 0.0D);

        private final BinaryOperation operation;

        Operation(BinaryOperation operation) {
            this.operation = operation;
        }

        private double apply(double left, double right) {
            return operation.apply(left, right);
        }
    }

    private enum Function {
        MIN("min", 2) {
            @Override
            double apply(double[] values) {
                return Math.min(values[0], values[1]);
            }
        },
        MAX("max", 2) {
            @Override
            double apply(double[] values) {
                return Math.max(values[0], values[1]);
            }
        },
        CLAMP("clamp", 3) {
            @Override
            double apply(double[] values) {
                return Math.max(values[1], Math.min(values[0], values[2]));
            }
        },
        ABS("abs", 1) {
            @Override
            double apply(double[] values) {
                return Math.abs(values[0]);
            }
        },
        FLOOR("floor", 1) {
            @Override
            double apply(double[] values) {
                return Math.floor(values[0]);
            }
        },
        CEIL("ceil", 1) {
            @Override
            double apply(double[] values) {
                return Math.ceil(values[0]);
            }
        },
        ROUND("round", 1) {
            @Override
            double apply(double[] values) {
                return Math.round(values[0]);
            }
        };

        private final String name;
        private final int argumentCount;

        Function(String name, int argumentCount) {
            this.name = name;
            this.argumentCount = argumentCount;
        }

        private static Function fromName(String name) {
            for (Function value : values()) {
                if (value.name.equals(name)) return value;
            }
            return null;
        }

        private double apply(List<Node> arguments, ReactionFormulaContext context) {
            double[] values = new double[arguments.size()];
            for (int index = 0; index < arguments.size(); index++) {
                values[index] = arguments.get(index).evaluate(context);
            }
            return apply(values);
        }

        abstract double apply(double[] values);
    }

    @FunctionalInterface
    private interface BinaryOperation {
        double apply(double left, double right);
    }
}

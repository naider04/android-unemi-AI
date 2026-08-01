package com.example.data.rules

import android.util.Log

enum class TokenType {
    FOR, IN, IF, ELSE, VAL,
    IDENTIFIER, STRING, NUMBER, BOOLEAN, NULL,
    EQ, NEQ, LTE, GTE, LT, GT, ASSIGN,
    PLUS, MINUS, MUL, DIV,
    AND, OR, NOT,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, DOT, COMMA,
    EOF
}

data class Token(val type: TokenType, val value: String)

class Lexer(private val src: String) {
    private var pos = 0
    private val len = src.length

    private fun peek(): Char = if (pos < len) src[pos] else '\u0000'
    private fun advance(): Char = if (pos < len) src[pos++] else '\u0000'

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < len) {
            val c = peek()
            when {
                c.isWhitespace() -> { advance() }
                c == '/' && pos + 1 < len && src[pos + 1] == '/' -> {
                    while (pos < len && peek() != '\n') advance()
                }
                c.isLetter() || c == '_' -> {
                    val start = pos
                    while (peek().isLetterOrDigit() || peek() == '_') advance()
                    val word = src.substring(start, pos)
                    val type = when (word) {
                        "for" -> TokenType.FOR
                        "in" -> TokenType.IN
                        "if" -> TokenType.IF
                        "else" -> TokenType.ELSE
                        "val" -> TokenType.VAL
                        "var" -> TokenType.VAL
                        "let" -> TokenType.VAL
                        "true" -> TokenType.BOOLEAN
                        "false" -> TokenType.BOOLEAN
                        "null" -> TokenType.NULL
                        else -> TokenType.IDENTIFIER
                    }
                    tokens.add(Token(type, word))
                }
                c.isDigit() -> {
                    val start = pos
                    while (peek().isDigit() || peek() == '.') advance()
                    tokens.add(Token(TokenType.NUMBER, src.substring(start, pos)))
                }
                c == '"' -> {
                    advance()
                    val start = pos
                    while (pos < len && peek() != '"') {
                        advance()
                    }
                    val str = src.substring(start, pos)
                    if (peek() == '"') advance()
                    tokens.add(Token(TokenType.STRING, str))
                }
                c == '\'' -> {
                    advance()
                    val start = pos
                    while (pos < len && peek() != '\'') {
                        advance()
                    }
                    val str = src.substring(start, pos)
                    if (peek() == '\'') advance()
                    tokens.add(Token(TokenType.STRING, str))
                }
                c == '=' && pos + 1 < len && src[pos + 1] == '=' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.EQ, "=="))
                }
                c == '!' && pos + 1 < len && src[pos + 1] == '=' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.NEQ, "!="))
                }
                c == '<' && pos + 1 < len && src[pos + 1] == '=' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.LTE, "<="))
                }
                c == '>' && pos + 1 < len && src[pos + 1] == '=' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.GTE, ">="))
                }
                c == '&' && pos + 1 < len && src[pos + 1] == '&' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.AND, "&&"))
                }
                c == '|' && pos + 1 < len && src[pos + 1] == '|' -> {
                    advance(); advance()
                    tokens.add(Token(TokenType.OR, "||"))
                }
                c == '=' -> { advance(); tokens.add(Token(TokenType.ASSIGN, "=")) }
                c == '<' -> { advance(); tokens.add(Token(TokenType.LT, "<")) }
                c == '>' -> { advance(); tokens.add(Token(TokenType.GT, ">")) }
                c == '+' -> { advance(); tokens.add(Token(TokenType.PLUS, "+")) }
                c == '-' -> { advance(); tokens.add(Token(TokenType.MINUS, "-")) }
                c == '*' -> { advance(); tokens.add(Token(TokenType.MUL, "*")) }
                c == '/' -> { advance(); tokens.add(Token(TokenType.DIV, "/")) }
                c == '!' -> { advance(); tokens.add(Token(TokenType.NOT, "!")) }
                c == '(' -> { advance(); tokens.add(Token(TokenType.LPAREN, "(")) }
                c == ')' -> { advance(); tokens.add(Token(TokenType.RPAREN, ")")) }
                c == '{' -> { advance(); tokens.add(Token(TokenType.LBRACE, "{")) }
                c == '}' -> { advance(); tokens.add(Token(TokenType.RBRACE, "}")) }
                c == '[' -> { advance(); tokens.add(Token(TokenType.LBRACKET, "[")) }
                c == ']' -> { advance(); tokens.add(Token(TokenType.RBRACKET, "]")) }
                c == '.' -> { advance(); tokens.add(Token(TokenType.DOT, ".")) }
                c == ',' -> { advance(); tokens.add(Token(TokenType.COMMA, ",")) }
                else -> {
                    advance()
                }
            }
        }
        tokens.add(Token(TokenType.EOF, ""))
        return tokens
    }
}

sealed interface ASTNode {
    class Block(val statements: List<ASTNode>) : ASTNode
    class ForLoop(val varName: String, val collectionName: String, val body: Block) : ASTNode
    class IfStatement(val condition: ASTNode, val thenBlock: Block, val elseBlock: Block? = null) : ASTNode
    class VarDeclaration(val varName: String, val valueExpr: ASTNode) : ASTNode
    class Assignment(val varName: String, val valueExpr: ASTNode) : ASTNode
    class FunctionCall(val name: String, val arguments: List<ASTNode>) : ASTNode
    
    class BinaryOp(val left: ASTNode, val op: String, val right: ASTNode) : ASTNode
    class UnaryOp(val op: String, val expr: ASTNode) : ASTNode
    class MemberAccess(val obj: ASTNode, val member: String) : ASTNode
    class Identifier(val name: String) : ASTNode
    class StringLiteral(val value: String) : ASTNode
    class NumberLiteral(val value: Double) : ASTNode
    class BooleanLiteral(val value: Boolean) : ASTNode
    class ListLiteral(val elements: List<ASTNode>) : ASTNode
    object NullLiteral : ASTNode
}

class Parser(private val tokens: List<Token>) {
    private var cur = 0

    private fun peek(): Token = tokens[cur]
    private fun advance(): Token = tokens[cur++]
    private fun match(type: TokenType): Boolean {
        if (peek().type == type) {
            advance()
            return true
        }
        return false
    }
    private fun consume(type: TokenType, message: String): Token {
        val t = peek()
        if (t.type == type) {
            return advance()
        }
        throw RuntimeException("$message (found token: $t)")
    }

    fun parse(): ASTNode.Block {
        val statements = mutableListOf<ASTNode>()
        while (peek().type != TokenType.EOF) {
            statements.add(parseStatement())
        }
        return ASTNode.Block(statements)
    }

    private fun parseStatement(): ASTNode {
        return when (peek().type) {
            TokenType.FOR -> parseForLoop()
            TokenType.IF -> parseIfStatement()
            TokenType.VAL -> parseVarDeclaration()
            TokenType.LBRACE -> parseBlock()
            else -> {
                val expr = parseExpression()
                if (expr is ASTNode.Identifier && peek().type == TokenType.ASSIGN) {
                    advance()
                    val value = parseExpression()
                    ASTNode.Assignment(expr.name, value)
                } else {
                    expr
                }
            }
        }
    }

    private fun parseForLoop(): ASTNode {
        consume(TokenType.FOR, "Expected 'for'")
        val hasParen = match(TokenType.LPAREN)
        val varName = consume(TokenType.IDENTIFIER, "Expected loop variable name").value
        consume(TokenType.IN, "Expected 'in'")
        val collectionName = consume(TokenType.IDENTIFIER, "Expected collection name").value
        if (hasParen) {
            consume(TokenType.RPAREN, "Expected closing paren ')'")
        }
        val body = parseBlock()
        return ASTNode.ForLoop(varName, collectionName, body)
    }

    private fun parseIfStatement(): ASTNode {
        consume(TokenType.IF, "Expected 'if'")
        consume(TokenType.LPAREN, "Expected open paren '('")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected closing paren ')'")
        val thenBlock = parseBlock()
        var elseBlock: ASTNode.Block? = null
        if (match(TokenType.ELSE)) {
            elseBlock = if (peek().type == TokenType.IF) {
                ASTNode.Block(listOf(parseIfStatement()))
            } else {
                parseBlock()
            }
        }
        return ASTNode.IfStatement(condition, thenBlock, elseBlock)
    }

    private fun parseVarDeclaration(): ASTNode {
        consume(TokenType.VAL, "Expected 'val'")
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").value
        consume(TokenType.ASSIGN, "Expected '='")
        val value = parseExpression()
        return ASTNode.VarDeclaration(name, value)
    }

    private fun parseBlock(): ASTNode.Block {
        consume(TokenType.LBRACE, "Expected '{'")
        val statements = mutableListOf<ASTNode>()
        while (peek().type != TokenType.RBRACE && peek().type != TokenType.EOF) {
            statements.add(parseStatement())
        }
        consume(TokenType.RBRACE, "Expected '}'")
        return ASTNode.Block(statements)
    }

    private fun parseExpression(): ASTNode {
        return parseLogicalOr()
    }

    private fun parseLogicalOr(): ASTNode {
        var node = parseLogicalAnd()
        while (match(TokenType.OR)) {
            val right = parseLogicalAnd()
            node = ASTNode.BinaryOp(node, "||", right)
        }
        return node
    }

    private fun parseLogicalAnd(): ASTNode {
        var node = parseEquality()
        while (match(TokenType.AND)) {
            val right = parseEquality()
            node = ASTNode.BinaryOp(node, "&&", right)
        }
        return node
    }

    private fun parseEquality(): ASTNode {
        var node = parseComparison()
        while (peek().type == TokenType.EQ || peek().type == TokenType.NEQ) {
            val op = advance().value
            val right = parseComparison()
            node = ASTNode.BinaryOp(node, op, right)
        }
        return node
    }

    private fun parseComparison(): ASTNode {
        var node = parseAdditive()
        while (peek().type == TokenType.LT || peek().type == TokenType.GT ||
            peek().type == TokenType.LTE || peek().type == TokenType.GTE) {
            val op = advance().value
            val right = parseAdditive()
            node = ASTNode.BinaryOp(node, op, right)
        }
        return node
    }

    private fun parseAdditive(): ASTNode {
        var node = parseMultiplicative()
        while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
            val op = advance().value
            val right = parseMultiplicative()
            node = ASTNode.BinaryOp(node, op, right)
        }
        return node
    }

    private fun parseMultiplicative(): ASTNode {
        var node = parseUnary()
        while (peek().type == TokenType.MUL || peek().type == TokenType.DIV) {
            val op = advance().value
            val right = parseUnary()
            node = ASTNode.BinaryOp(node, op, right)
        }
        return node
    }

    private fun parseUnary(): ASTNode {
        if (peek().type == TokenType.NOT || peek().type == TokenType.MINUS) {
            val op = advance().value
            val expr = parseUnary()
            return ASTNode.UnaryOp(op, expr)
        }
        return parsePrimary()
    }

    private fun parsePrimary(): ASTNode {
        val t = peek()
        var node: ASTNode = when (t.type) {
            TokenType.IDENTIFIER -> {
                advance()
                if (peek().type == TokenType.LPAREN) {
                    advance()
                    val args = mutableListOf<ASTNode>()
                    if (peek().type != TokenType.RPAREN) {
                        args.add(parseExpression())
                        while (match(TokenType.COMMA)) {
                            args.add(parseExpression())
                        }
                    }
                    consume(TokenType.RPAREN, "Expected ')'")
                    ASTNode.FunctionCall(t.value, args)
                } else {
                    ASTNode.Identifier(t.value)
                }
            }
            TokenType.STRING -> { advance(); ASTNode.StringLiteral(t.value) }
            TokenType.NUMBER -> { advance(); ASTNode.NumberLiteral(t.value.toDouble()) }
            TokenType.BOOLEAN -> { advance(); ASTNode.BooleanLiteral(t.value == "true") }
            TokenType.LBRACKET -> {
                advance()
                val elements = mutableListOf<ASTNode>()
                if (peek().type != TokenType.RBRACKET) {
                    elements.add(parseExpression())
                    while (match(TokenType.COMMA)) {
                        elements.add(parseExpression())
                    }
                }
                consume(TokenType.RBRACKET, "Expected ']'")
                ASTNode.ListLiteral(elements)
            }
            TokenType.NULL -> { advance(); ASTNode.NullLiteral }
            TokenType.LPAREN -> {
                advance()
                val expr = parseExpression()
                consume(TokenType.RPAREN, "Expected ')'")
                expr
            }
            else -> throw RuntimeException("Unexpected token: $t")
        }

        while (match(TokenType.DOT)) {
            val member = consume(TokenType.IDENTIFIER, "Expected property name after '.'")
            if (match(TokenType.LPAREN)) {
                val args = mutableListOf<ASTNode>()
                if (peek().type != TokenType.RPAREN) {
                    args.add(parseExpression())
                    while (match(TokenType.COMMA)) {
                        args.add(parseExpression())
                    }
                }
                consume(TokenType.RPAREN, "Expected ')'")
                node = ASTNode.FunctionCall(member.value, listOf(node) + args)
            } else {
                node = ASTNode.MemberAccess(node, member.value)
            }
        }

        return node
    }
}

class ScriptInterpreter(
    private val courses: List<Map<String, Any?>>,
    private val activities: List<Map<String, Any?>>,
    private val onNotify: (title: String, body: String, triggerKey: String, isAlarm: Boolean) -> Unit
) {
    private val globals = mutableMapOf<String, Any?>()
    private var activeLoopObject: Map<String, Any?>? = null

    init {
        globals["now"] = System.currentTimeMillis()
        globals["courses"] = courses
        globals["activities"] = activities
        
        globals["quizzes"] = activities.filter { (it["moduleType"] as? String) == "quiz" }
        globals["assignments"] = activities.filter { (it["moduleType"] as? String) == "assign" }
    }

    fun execute(scriptText: String) {
        try {
            val lexer = Lexer(scriptText)
            val tokens = lexer.tokenize()
            val parser = Parser(tokens)
            val block = parser.parse()
            
            val env = globals.toMutableMap()
            evaluateBlock(block, env)
        } catch (e: Exception) {
            Log.e("ScriptInterpreter", "Failed executing script: $scriptText", e)
        }
    }

    private fun evaluateBlock(block: ASTNode.Block, env: MutableMap<String, Any?>) {
        for (stmt in block.statements) {
            evaluate(stmt, env)
        }
    }

    private fun evaluate(node: ASTNode, env: MutableMap<String, Any?>): Any? {
        return when (node) {
            is ASTNode.Block -> {
                evaluateBlock(node, env)
                null
            }
            is ASTNode.ForLoop -> {
                val collection = evaluate(ASTNode.Identifier(node.collectionName), env)
                if (collection is List<*>) {
                    for (item in collection) {
                        @Suppress("UNCHECKED_CAST")
                        val itemMap = item as? Map<String, Any?> ?: continue
                        val loopEnv = env.toMutableMap()
                        loopEnv[node.varName] = itemMap
                        
                        val prevLoop = activeLoopObject
                        activeLoopObject = itemMap
                        evaluateBlock(node.body, loopEnv)
                        activeLoopObject = prevLoop
                    }
                }
                null
            }
            is ASTNode.IfStatement -> {
                val cond = isTrue(evaluate(node.condition, env))
                if (cond) {
                    evaluateBlock(node.thenBlock, env)
                } else if (node.elseBlock != null) {
                    evaluateBlock(node.elseBlock, env)
                }
                null
            }
            is ASTNode.VarDeclaration -> {
                env[node.varName] = evaluate(node.valueExpr, env)
                null
            }
            is ASTNode.Assignment -> {
                if (env.containsKey(node.varName)) {
                    env[node.varName] = evaluate(node.valueExpr, env)
                }
                null
            }
            is ASTNode.FunctionCall -> {
                val args = node.arguments.map { evaluate(it, env) }
                when (node.name) {
                    "notify" -> {
                        val title = args.getOrNull(0)?.toString() ?: "Notification triggered"
                        val body = args.getOrNull(1)?.toString() ?: ""
                        
                        val loopObj = activeLoopObject
                        val triggerKey = when {
                            loopObj != null && loopObj.containsKey("moodleActivityId") -> "activity_${loopObj["moodleActivityId"]}"
                            loopObj != null && loopObj.containsKey("moodleCourseId") -> "course_${loopObj["moodleCourseId"]}"
                            else -> "global"
                        }
                        onNotify(title, body, triggerKey, false)
                        null
                    }
                    "alarm", "trigger", "scheduleAlarm" -> {
                        val title = args.getOrNull(0)?.toString() ?: "Alarm triggered"
                        val body = args.getOrNull(1)?.toString() ?: ""
                        
                        val loopObj = activeLoopObject
                        val triggerKey = when {
                            loopObj != null && loopObj.containsKey("moodleActivityId") -> "activity_${loopObj["moodleActivityId"]}"
                            loopObj != null && loopObj.containsKey("moodleCourseId") -> "course_${loopObj["moodleCourseId"]}"
                            else -> "global"
                        }
                        onNotify(title, body, triggerKey, true)
                        null
                    }
                    "now" -> System.currentTimeMillis()
                    "hours", "hour" -> {
                        val num = (args.getOrNull(0) as? Number)?.toDouble() ?: 0.0
                        (num * 3600.0 * 1000.0).toLong()
                    }
                    "days", "day" -> {
                        val num = (args.getOrNull(0) as? Number)?.toDouble() ?: 0.0
                        (num * 86400.0 * 1000.0).toLong()
                    }
                    "minutes", "minute" -> {
                        val num = (args.getOrNull(0) as? Number)?.toDouble() ?: 0.0
                        (num * 60.0 * 1000.0).toLong()
                    }
                    "push" -> {
                        val list = args.getOrNull(0) as? MutableList<Any?>
                        val valToPush = args.getOrNull(1)
                        if (list != null) {
                            list.add(valToPush)
                        }
                        null
                    }
                    "join" -> {
                        val list = args.getOrNull(0) as? List<*>
                        val delimiter = args.getOrNull(1)?.toString() ?: ", "
                        list?.joinToString(delimiter) ?: ""
                    }
                    "isToday" -> {
                        val ts = (args.getOrNull(0) as? Number)?.toLong()
                        if (ts != null) {
                            isSameDay(ts, System.currentTimeMillis())
                        } else {
                            false
                        }
                    }
                    "isTomorrow" -> {
                        val ts = (args.getOrNull(0) as? Number)?.toLong()
                        if (ts != null) {
                            isSameDay(ts, System.currentTimeMillis() + 86400000L)
                        } else {
                            false
                        }
                    }
                    "isYesterday" -> {
                        val ts = (args.getOrNull(0) as? Number)?.toLong()
                        if (ts != null) {
                            isSameDay(ts, System.currentTimeMillis() - 86400000L)
                        } else {
                            false
                        }
                    }
                    "startOfDay" -> {
                        val ts = (args.getOrNull(0) as? Number)?.toLong() ?: System.currentTimeMillis()
                        val cal = java.util.Calendar.getInstance().apply {
                            timeInMillis = ts
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        cal.timeInMillis
                    }
                    "endOfDay" -> {
                        val ts = (args.getOrNull(0) as? Number)?.toLong() ?: System.currentTimeMillis()
                        val cal = java.util.Calendar.getInstance().apply {
                            timeInMillis = ts
                            set(java.util.Calendar.HOUR_OF_DAY, 23)
                            set(java.util.Calendar.MINUTE, 59)
                            set(java.util.Calendar.SECOND, 59)
                            set(java.util.Calendar.MILLISECOND, 999)
                        }
                        cal.timeInMillis
                    }
                    else -> {
                        if (args.isNotEmpty()) {
                            val receiver = args[0]
                            when (node.name) {
                                "hours", "hour" -> {
                                    val num = (receiver as? Number)?.toDouble() ?: 0.0
                                    (num * 3600.0 * 1000.0).toLong()
                                }
                                "days", "day" -> {
                                    val num = (receiver as? Number)?.toDouble() ?: 0.0
                                    (num * 86400.0 * 1000.0).toLong()
                                }
                                "minutes", "minute" -> {
                                    val num = (receiver as? Number)?.toDouble() ?: 0.0
                                    (num * 60.0 * 1000.0).toLong()
                                }
                                else -> null
                            }
                        } else {
                            null
                        }
                    }
                }
            }
            is ASTNode.BinaryOp -> {
                val leftVal = evaluate(node.left, env)
                if (node.op == "&&") {
                    return isTrue(leftVal) && isTrue(evaluate(node.right, env))
                }
                if (node.op == "||") {
                    return isTrue(leftVal) || isTrue(evaluate(node.right, env))
                }

                val rightVal = evaluate(node.right, env)
                evaluateBinaryOp(leftVal, node.op, rightVal)
            }
            is ASTNode.UnaryOp -> {
                val exprVal = evaluate(node.expr, env)
                when (node.op) {
                    "!" -> !isTrue(exprVal)
                    "-" -> {
                        if (exprVal is Number) -exprVal.toDouble() else 0.0
                    }
                    else -> exprVal
                }
            }
            is ASTNode.MemberAccess -> {
                val objVal = evaluate(node.obj, env)
                if (objVal is Map<*, *>) {
                    objVal[node.member]
                } else if (objVal is List<*>) {
                    if (node.member == "length" || node.member == "size") {
                        objVal.size
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            is ASTNode.Identifier -> {
                when (node.name) {
                    "now" -> System.currentTimeMillis()
                    else -> env[node.name]
                }
            }
            is ASTNode.StringLiteral -> node.value
            is ASTNode.NumberLiteral -> node.value
            is ASTNode.BooleanLiteral -> node.value
            is ASTNode.ListLiteral -> {
                node.elements.map { evaluate(it, env) }.toMutableList()
            }
            is ASTNode.NullLiteral -> null
        }
    }

    private fun isTrue(v: Any?): Boolean {
        if (v == null) return false
        return when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            else -> true
        }
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
               cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun evaluateBinaryOp(left: Any?, op: String, right: Any?): Any? {
        if (left is Number && right is Number) {
            val l = left.toDouble()
            val r = right.toDouble()
            return when (op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "/" -> if (r != 0.0) l / r else 0.0
                "<" -> l < r
                ">" -> l > r
                "<=" -> l <= r
                ">=" -> l >= r
                "==" -> l == r
                "!=" -> l != r
                else -> null
            }
        }
        return when (op) {
            "==" -> left == right
            "!=" -> left != right
            "+" -> left.toString() + right.toString()
            else -> null
        }
    }
}

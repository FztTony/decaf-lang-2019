package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.LocalScope;
import decaf.frontend.scope.Scope;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyScope;

import java.util.ArrayList;
import java.util.Optional;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Typer(Config config) {
        super("typer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        ctx.open(method.symbol.scope);
        if (!method.isAbstract()) {
            method.body.accept(this, ctx);
            if (!method.symbol.type.returnType.isVoidType() && !method.body.returns) {
                issue(new MissingReturnError(method.body.pos));
            }
        }
        ctx.close();
    }

    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx) {
        Type retType;
        var argTypes = new ArrayList<Type>();
        boolean err = false;
        lambdaLevel++;
        ctx.open(lambda.scope);
        for (var param : lambda.params) {
            param.accept(this, ctx);
            argTypes.add(param.symbol.type);
            if (param.symbol.type.hasError())
                err = true;
        }
        if (lambda.withBody()) {
            lambda.body.accept(this, ctx);
            retType = lambda.body.retType;
            if (retType == null) {
                retType = BuiltInType.VOID;
            }
            if (!retType.isVoidType() && !lambda.body.returns) {
                issue(new MissingReturnError(lambda.body.pos));
            }
            if (retType.isConflict()) {
                issue(new IncompatRetError(lambda.body.pos));
            }
        } else {
            ctx.open(lambda.scope.nestedLocalScope());
            lambda.expr.accept(this, ctx);
            retType = lambda.expr.type;
            ctx.close();
        }
        ctx.close();
        lambdaLevel--;
        if (err || retType.hasError()) {
            lambda.type = BuiltInType.ERROR;
        } else {
            var lambdaType = new FunType(retType, argTypes);
            lambda.type = lambdaType;
            lambda.symbol.setType(lambdaType);
        }
    }
    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;
    private int lambdaLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
            block.updateRetType(stmt);
        }
        ctx.close();
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);
        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;

        if (lt.noError()) {
            if (rt.noError() && !rt.subtypeOf(lt)) {
                //System.out.println(stmt.pos.toString() +  " @@@ " + lt.toString()+  " @ " + "="+  " @ " + rt.toString());
                issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
            }
            if (stmt.lhs instanceof Tree.VarSel) {
                var lvar = (Tree.VarSel) stmt.lhs;
                if (lvar.isMethodName) {
                    issue(new AssignMethodError(stmt.pos, lvar.name));
                } else if ((lambdaLevel > 0) && (lvar.symbol != null) && (lvar.symbol.domain() != ctx.currentScope()) && (!lvar.symbol.isMemberVar())) {
                    Scope curr = ctx.currentScope();
                    while (lvar.symbol.domain() != curr && curr.isLocalScope()) {
                        curr = ((LocalScope) curr).parent;
                    }
                    if (lvar.symbol.domain() != curr) {
                        issue(new AssignCapVarError(stmt.pos));
                    }
                }
            }
        }
    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }


    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> {b.accept(this, ctx);});
        stmt.updateRetType(stmt.trueBranch);
        stmt.falseBranch.ifPresent(stmt::updateRetType);
        // if-stmt returns a value iff both branches return
        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loop.updateRetType(loop.body);
        loopLevel--;
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
            loop.updateRetType(stmt);
        }
        loopLevel--;
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        //System.out.println(stmt + " @ " + stmt.pos);
        stmt.expr.ifPresent(e -> e.accept(this, ctx));
        var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
        if (lambdaLevel == 0) {
            var expected = ctx.currentMethod().type.returnType;
            if (actual.noError() && !actual.subtypeOf(expected)) {
                issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
            }
        }
        stmt.retType = actual;
        stmt.returns = stmt.expr.isPresent();
    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;
        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            //System.out.println(expr.pos.toString() +  " @ " + t1.toString()+  " @ " + Tree.opStr(expr.op)+  " @ " + t2.toString());
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);
        if (clazz.isPresent()) {
            if (!clazz.get().isAbstract()) {
                expr.symbol = clazz.get();
                expr.type = expr.symbol.type;
            }
            else {
                issue(new NewAbstractClassError(expr.pos, expr.clazz.name));
                expr.type = BuiltInType.ERROR;
            }
        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }

    private boolean allowClassNameVar = false;

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {
        //System.out.println(expr.pos.toString() + " @ " + expr.toString());
        if (expr.receiver.isEmpty()) {
            //System.out.println(expr.pos.toString() + " @a " + expr.toString());
            // Variable, which should be complicated since a legal variable could refer to a local var,
            // a visible member var, and a class name.
            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            if (symbol.isPresent()) {
                //System.out.println(expr.pos.toString() + " @d " + expr.toString());
                if (symbol.get().isVarSymbol()) {
                    //System.out.println(expr.pos.toString() + " @e " + expr.toString());
                    var var = (VarSymbol) symbol.get();
                    expr.symbol = var;
                    expr.type = var.type;
                    //System.out.println(expr.pos.toString() + " @e0 " + expr.toString());
                    if (var.isMemberVar()) {
                        //System.out.println(expr.pos.toString() + " @e1 " + expr.toString());
                        if (ctx.currentMethod().isStatic()) {
                            //System.out.println(expr.pos.toString() + " @e2 " + expr.toString());
                            issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                        } else {
                            //System.out.println(expr.pos.toString() + " @e3 " + expr.toString());
                            expr.setThis();
                        }
                        //System.out.println(expr.pos.toString() + " @e4 " + expr.toString());
                    }
                    //System.out.println(expr.pos.toString() + " @e5 " + expr.toString());
                    return;
                } else if (symbol.get().isClassSymbol() && allowClassNameVar) { // special case: a class name
                    //System.out.println(expr.pos.toString() + " @f " + expr.toString());
                    var clazz = (ClassSymbol) symbol.get();
                    expr.type = clazz.type;
                    expr.isClassName = true;
                    return;
                } else if (symbol.get().isMethodSymbol()) {
                    //System.out.println(expr.pos.toString() + " @g " + expr.toString());
                    var method = (MethodSymbol) symbol.get();
                    expr.type = method.type;
                    expr.isMethodName = true;
                    if (!method.isStatic() && ctx.currentMethod().isStatic()) {
                        issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                    }
                    return;
                } else if (symbol.get().isLambdaSymbol()) {
                    //System.out.println(expr.pos.toString() + " @h " + expr.toString());
                    var lambda = (LambdaSymbol) symbol.get();
                    expr.type = lambda.type;
                    return;
                }

            }

            //System.out.println(expr.pos.toString() + " @b " + expr.toString());
            expr.type = BuiltInType.ERROR;
            issue(new UndeclVarError(expr.pos, expr.name));
            return;
        }

        //System.out.println(expr.pos.toString() + " @c " + expr.toString() + " @ " + expr.receiver.get().toString());
        // has receiver
        var receiver = expr.receiver.get();
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        var rt = receiver.type;
        expr.type = BuiltInType.ERROR;

        //System.out.println(expr.pos.toString() + " @iii " + expr.toString() + " @ " + rt.toString() + " @ " + receiver.toString());
        if (receiver instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) receiver;
            var v2 = expr.variable;
            //System.out.println(expr.pos.toString() + " @i1 " + v1.toString() + " @ " + v2.toString());
            if (v1.isClassName) {
                //System.out.println(expr.pos.toString() + " @i2 " + v1.toString() + " @ " + v2.toString());
                var clazz = ctx.getClass(v1.name);
                var symbol = clazz.scope.lookup(v2.name);
                if (symbol.isPresent() && symbol.get().isMethodSymbol()) {
                    //System.out.println(expr.pos.toString() + " @i3 " + v1.toString() + " @ " + v2.toString());
                    if (!((MethodSymbol) symbol.get()).isStatic()) {
                        //System.out.println(expr.pos.toString() + " @i4 " + v1.toString() + " @ " + v2.toString());
                        // static method can't access non-static methods
                        issue(new NotClassFieldError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                        return;
                    }
                    expr.isStatic = true;
                    expr.isMethodName = true;
                    expr.calleeName = v1.name;
                    expr.type = symbol.get().type;
                } else if (symbol.isEmpty()) {
                    //System.out.println(expr.pos.toString() + " @i5 " + v1.toString() + " @ " + v2.toString());
                    issue(new FieldNotFoundError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                } else {
                    //System.out.println(expr.pos.toString() + " @i6 " + v1.toString() + " @ " + v2.toString());
                    issue(new NotClassFieldError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                }
                //System.out.println(expr.pos.toString() + " @i7 " + v1.toString() + " @ " + v2.toString());
                return;
            }
        }
        if (!rt.noError()) {
            //System.out.println(expr.pos.toString() + " @i8 ");
            return;
        } else if (rt.isArrayType() && expr.variable.name.equals("length")) { // Special case: array.length()
            //System.out.println(expr.pos.toString() + " @i9 ");
            expr.isArrayLength = true;
            expr.type = new FunType(BuiltInType.INT, new ArrayList<>());
            return;
        } else if (!rt.isClassType()) {
            //System.out.println(expr.pos.toString() + " @i10 ");
            issue(new NotClassFieldError(expr.pos, expr.name, rt.toString()));
            return;
        }
        //System.out.println(expr.pos.toString() + " @ii " + expr.toString());
        expr.calleeName = ((ClassType) rt).name;
        var ct = (ClassType) rt;
        var field = ctx.getClass(ct.name).scope.lookup(expr.name);
        if (field.isPresent() && field.get().isVarSymbol()) {
            var var = (VarSymbol) field.get();
            if (var.isMemberVar()) {
                expr.symbol = var;
                expr.type = var.type;
                if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                    // member vars are protected
                    issue(new FieldNotAccessError(expr.pos, expr.name, ct.toString()));
                }
            }
        } else if (field.isPresent() && field.get().isMethodSymbol()) {
            expr.type = ((MethodSymbol) field.get()).type;
            expr.isMethodName = true;
        } else if (field.isEmpty()) {
            issue(new FieldNotFoundError(expr.pos, expr.name, ct.toString()));
        } else {
            issue(new NotClassFieldError(expr.pos, expr.name, ct.toString()));
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if (at.hasError()) {
            expr.type = BuiltInType.ERROR;
            return;
        }

        if (!at.isArrayType()) {
            issue(new NotArrayError(expr.array.pos));
            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        expr.callee.accept(this, ctx);
        expr.type = BuiltInType.ERROR;
        if (expr.callee.type.hasError()) {
            return;
        }

        if (!expr.callee.type.isFuncType()) {
            issue(new NotCallableTypeError(expr.pos, expr.callee.type.toString()));
            return;
        }

        var methodType = (FunType) expr.callee.type;
        expr.type = methodType.returnType;

        var args = expr.args;
        for (var arg : args) {
            arg.accept(this, ctx);
        }
        var methodArgs = methodType.argTypes;
        if (methodArgs.size() != args.size()) {
            if (expr.callee instanceof Tree.VarSel) {//it's a function
                issue(new BadArgCountError(expr.pos, ((Tree.VarSel) expr.callee).name, methodArgs.size(), args.size()));
            } else {//it's a lambda function
                issue(new BadLambdaArgsError(expr.pos, methodArgs.size(), args.size()));
            }
        }
        for (int i = 0; i < java.lang.Math.min(methodArgs.size(), args.size()); i++) {
            Type t1 = methodType.argTypes.get(i);
            Type t2 = args.get(i).type;
            if (t2.noError() && !t2.subtypeOf(t1)) {
                issue(new BadArgTypeError(args.get(i).pos, i + 1, t2.toString(), t1.toString()));
            }
        }
    }

    private void typeCall(Tree.Call call, boolean thisClass, String className, ScopeStack ctx, boolean requireStatic) {
        var clazz = thisClass ? ctx.currentClass() : ctx.getClass(className);
        var callee = (Tree.VarSel) call.callee;
        var symbol = clazz.scope.lookup(callee.name);
        if (symbol.isPresent()) {
            if (symbol.get().isMethodSymbol()) {
                var method = (MethodSymbol) symbol.get();
                call.symbol = method;
                call.type = method.type.returnType;
                if (requireStatic && !method.isStatic()) {
                    issue(new NotClassFieldError(call.pos, callee.name, clazz.type.toString()));
                    return;
                }

                // Cannot call this's member methods in a static method
                if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
                    issue(new RefNonStaticError(call.pos, ctx.currentMethod().name, method.name));
                }

                // typing args
                var args = call.args;
                for (var arg : args) {
                    arg.accept(this, ctx);
                }

                // check signature compatibility
                if (method.type.arity() != args.size()) {
                    issue(new BadArgCountError(call.pos, method.name, method.type.arity(), args.size()));
                }
                var iter1 = method.type.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }
            } else {
                issue(new NotClassMethodError(call.pos, callee.name, clazz.type.toString()));
            }
        } else {
            issue(new FieldNotFoundError(call.pos, callee.name, clazz.type.toString()));
        }
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {
        //System.out.println(stmt);
        if (stmt.initVal.isEmpty()) return;

        var initVal = stmt.initVal.get();
        localVarDefPos = Optional.ofNullable(stmt.id.pos);
        initVal.accept(this, ctx);
        localVarDefPos = Optional.empty();
        var lt = stmt.symbol.type;
        var rt = initVal.type;
        //System.out.println(stmt.symbol);
        //System.out.println(lt);
        //System.out.println(rt);
        if (lt != null) {//now it can be functype
            if (lt.hasError() || rt.hasError())
                return;
            if (!rt.subtypeOf(lt)){// && (lt.isFuncType() || !rt.subtypeOf(lt))) {
                //System.out.println(stmt.assignPos +  " @@ " + lt.toString() +  " @@ " + "=" +  " @@ " + rt.toString());
                issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
            }
        }
        else {
            if (initVal.type.eq(BuiltInType.VOID)) {
                issue(new BadVarTypeError(stmt.pos, stmt.name));
                rt = BuiltInType.ERROR;
            }
            stmt.symbol.type = rt;
        }
    }

    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();
}

package decaf.frontend.typecheck;

import decaf.driver.ErrorIssuer;
import decaf.driver.error.BadArrElementError;
import decaf.driver.error.BadTLambdaArgError;
import decaf.driver.error.ClassNotFoundError;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.tree.Tree;
import decaf.frontend.tree.Visitor;
import decaf.frontend.type.BuiltInType;
import decaf.frontend.type.FunType;
import decaf.frontend.type.Type;

import java.util.ArrayList;

/**
 * Infer the types of type literals in the abstract syntax tree.
 * <p>
 * These visitor methods are shared by {@link Namer} and {@link Typer}.
 */
public interface TypeLitVisited extends Visitor<ScopeStack>, ErrorIssuer {

    // visiting types
    @Override
    default void visitTInt(Tree.TInt that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    default void visitTBool(Tree.TBool that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    default void visitTString(Tree.TString that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    default void visitTVoid(Tree.TVoid that, ScopeStack ctx) {
        that.type = BuiltInType.VOID;
    }

    @Override
    default void visitTClass(Tree.TClass typeClass, ScopeStack ctx) {
        var c = ctx.lookupClass(typeClass.id.name);
        if (c.isEmpty()) {
            issue(new ClassNotFoundError(typeClass.pos, typeClass.id.name));
            typeClass.type = BuiltInType.ERROR;
        } else {
            typeClass.type = c.get().type;
        }
    }

    @Override
    default void visitTArray(Tree.TArray typeArray, ScopeStack ctx) {
        typeArray.elemType.accept(this, ctx);
        if (typeArray.elemType.type.eq(BuiltInType.ERROR)) {
            typeArray.type = BuiltInType.ERROR;
        } else if (typeArray.elemType.type.eq(BuiltInType.VOID)) {
            issue(new BadArrElementError(typeArray.pos));
            typeArray.type = BuiltInType.ERROR;
        } else {
            typeArray.type = new decaf.frontend.type.ArrayType(typeArray.elemType.type);
        }
    }
    @Override
    default void visitTLambda(Tree.TLambda typeLambda, ScopeStack ctx) {
        boolean err = false;
        typeLambda.functionType.accept(this, ctx);
        if (typeLambda.functionType.type.eq(BuiltInType.ERROR)) {
            err = true;
        }
        ArrayList<Type> types = new ArrayList<>();
        for (var type : typeLambda.types) {
            type.accept(this, ctx);
            if (type.type.eq(BuiltInType.ERROR)) {
                err = true;//with error
            } else if (type.type.eq(BuiltInType.VOID)) {
                issue(new BadTLambdaArgError(type.pos));
                err = true;//args can't be void type
            } else if (!err) {
                types.add(type.type);
            }
        }
        if (err) {
            typeLambda.type = BuiltInType.ERROR;
        } else {
            typeLambda.type = new FunType(typeLambda.functionType.type, types);
        }
    }
}

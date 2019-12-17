package decaf.frontend.symbol;

import decaf.frontend.scope.ClassScope;
import decaf.frontend.scope.FormalScope;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;
import decaf.frontend.type.Type;

/**
 * Method symbol, representing a method definition.
 */
public final class LambdaSymbol extends Symbol {

    public FunType type;

    /**
     * Associated formal scope of the method parameters.
     */
    public final LambdaScope scope;

    public final ClassSymbol owner;

    public LambdaSymbol(LambdaScope scope, Pos pos, ClassSymbol owner) {
        super("lambda@" + pos, null, pos);
        this.scope = scope;
        this.owner = owner;
    }

    @Override
    public ClassScope domain() {
        return (ClassScope) definedIn;
    }

    @Override
    public boolean isLambdaSymbol() {
        return true;
    }

    @Override
    protected String str() {
        return String.format("function %s : %s", name, type);
    }

    @Override
    public void setType(Type type) {
        assert type instanceof FunType;
        super.setType(type);
        this.type = (FunType) type;
    }

}
